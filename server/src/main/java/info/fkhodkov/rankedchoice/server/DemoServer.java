package info.fkhodkov.rankedchoice.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import info.fkhodkov.rankedchoice.core.ballot.BallotParseException;
import info.fkhodkov.rankedchoice.count.CountOptions;
import info.fkhodkov.rankedchoice.count.Election;
import info.fkhodkov.rankedchoice.count.ReportFormat;
import info.fkhodkov.rankedchoice.count.Rulesets;
import info.fkhodkov.rankedchoice.count.Tabulator;

/**
 * A demo web server that counts elections online through the library's {@link Tabulator}, on the
 * JDK's own HTTP server:
 *
 * <ul>
 * <li>{@code GET /api/methods}: the methods, the options each takes, the bundled rulesets, the
 * report formats and the server's limits, as JSON;
 * <li>{@code POST /api/count}: a {@link CountRequest} as JSON, answered with the report in the
 * format asked for (text by default), with any warning in {@code X-Count-Warning}; a count
 * refused is a 400 with {@code {"error": ...}}, naming options as the request does;
 * <li>everything else: the page under {@code static/}.
 * </ul>
 *
 * <p>{@link Limits} bound what it takes on: a request too large is a 413, a count asked for while
 * every place is taken a 503, one that runs out of time a 504.
 */
public final class DemoServer {

    /** Counts a request; the server's own is the library's, a test's may be anything. */
    interface Counter {
        String count(CountRequest request, ReportFormat format, List<String> warnings);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> OPTIONS = List.of("seats", "ruleset", "quota", "transfer", "rounding",
            "tiebreak", "tiedRankings", "minimumPreferences", "precision", "bordaScoring");
    private static final Pattern FLAG = Pattern.compile("--([a-z]+(?:-[a-z]+)*)");
    private static final Pattern STATIC_PATH = Pattern.compile("/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.[a-z]+");

    private final HttpServer http;
    private final ExecutorService counts;
    private final Semaphore places;
    private final Limits limits;
    private final Counter counter;

    private DemoServer(HttpServer http, Limits limits, Counter counter) {
        this.http = http;
        this.limits = limits;
        this.counter = counter;
        this.counts = Executors.newFixedThreadPool(limits.maxConcurrentCounts());
        this.places = new Semaphore(limits.maxConcurrentCounts());
    }

    /** Starts a server at the address (port 0 for any free one). */
    public static DemoServer start(InetSocketAddress address, Limits limits) throws IOException {
        return start(address, limits, DemoServer::countByTheLibrary);
    }

    static DemoServer start(InetSocketAddress address, Limits limits, Counter counter) throws IOException {
        HttpServer http = HttpServer.create(address, 0);
        DemoServer server = new DemoServer(http, limits, counter);
        http.createContext("/api/methods", exchange -> server.handle(exchange, server::methods));
        http.createContext("/api/count", exchange -> server.handle(exchange, server::count));
        http.createContext("/", exchange -> server.handle(exchange, server::page));
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        return server;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    /** Stops answering; a count under way is left to end on its own. */
    public void stop() {
        http.stop(0);
        counts.shutdownNow();
    }

    /** {@code [--port N] [--bind ADDRESS]}: port 8080 on this machine alone (127.0.0.1) by default. */
    public static void main(String[] args) throws IOException {
        int port = 8080;
        String bind = "127.0.0.1";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--bind" -> bind = args[++i];
                default -> {
                    System.err.println("usage: java -jar rankedchoice-server.jar [--port N] [--bind ADDRESS]");
                    System.exit(2);
                }
            }
        }
        DemoServer server = start(new InetSocketAddress(bind, port), Limits.DEFAULT);
        System.out.println("Counting at http://" + bind + ":" + server.port() + "/");
    }

    /** An answer: its status, content type and body. */
    private record Response(int status, String contentType, String body, Map<String, String> headers) {

        static Response json(int status, Object value) {
            try {
                return new Response(status, "application/json", JSON.writeValueAsString(value), Map.of());
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
        }

        static Response error(int status, String message) {
            return json(status, Map.of("error", message));
        }
    }

    private interface Handler {
        Response handle(HttpExchange exchange) throws IOException;
    }

    private void handle(HttpExchange exchange, Handler handler) throws IOException {
        try (exchange) {
            Response response;
            try {
                response = handler.handle(exchange);
            } catch (RuntimeException e) {
                response = Response.error(500, "an internal fault, please report it: " + e);
            }
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", response.contentType() + "; charset=utf-8");
            headers.set("X-Content-Type-Options", "nosniff");
            response.headers().forEach(headers::set);
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        }
    }

    private Response methods(HttpExchange exchange) {
        if (!exchange.getRequestMethod().equals("GET")) {
            return notAllowed("GET");
        }
        List<Map<String, Object>> methods = new ArrayList<>();
        for (Tabulator.Method method : Tabulator.METHODS) {
            List<String> refused = Tabulator.unusedOptions(method.name()).keySet().stream()
                    .map(DemoServer::fieldName).toList();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", method.name());
            entry.put("description", method.description());
            entry.put("options", OPTIONS.stream().filter(option -> !refused.contains(option)).toList());
            methods.add(entry);
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("methods", methods);
        answer.put("rulesets", Rulesets.ALL);
        answer.put("reportFormats", List.of("text", "csv", "json", "html"));
        answer.put("limits", Map.of("maxRequestBytes", limits.maxRequestBytes(),
                "maxConcurrentCounts", limits.maxConcurrentCounts(),
                "countTimeoutSeconds", limits.countTimeout().toSeconds()));
        return Response.json(200, answer);
    }

    private Response count(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            return notAllowed("POST");
        }
        byte[] body = readBody(exchange);
        if (body == null) {
            return Response.error(413, "the request is larger than this server takes ("
                    + limits.maxRequestBytes() / 1024 + " KB)");
        }
        CountRequest request;
        try {
            request = JSON.readValue(body, CountRequest.class);
        } catch (IOException e) {
            String why = e instanceof JsonProcessingException json ? json.getOriginalMessage() : e.getMessage();
            return Response.error(400, "the request isn't a count as JSON: " + why);
        }
        if (request == null || request.ballots() == null || request.ballots().isBlank()) {
            return Response.error(400, "ballots is required: the ballot file's text, BLT or CSV");
        }
        ReportFormat format;
        try {
            format = ReportFormat.of(request.reportFormat() != null ? request.reportFormat() : "text");
        } catch (IllegalArgumentException e) {
            return Response.error(400, e.getMessage());
        }
        if (!places.tryAcquire()) {
            return Response.error(503, "every place for a count is taken; try again in a minute");
        }
        List<String> warnings = new ArrayList<>();
        Future<String> report;
        try {
            report = counts.submit(() -> {
                try {
                    return counter.count(request, format, warnings);
                } finally {
                    places.release();
                }
            });
        } catch (RuntimeException e) {
            places.release();
            throw e;
        }
        try {
            String text = report.get(limits.countTimeout().toMillis(), TimeUnit.MILLISECONDS);
            Map<String, String> headers = new LinkedHashMap<>();
            if (format == ReportFormat.HTML) {
                // The report is this program's own markup, every name escaped; even so, it may not run scripts.
                headers.put("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
            }
            synchronized (warnings) {
                if (!warnings.isEmpty()) {
                    headers.put("X-Count-Warning", String.join(" | ", warnings).replaceAll("[\\r\\n]+", " "));
                }
            }
            return new Response(200, contentType(format), text, headers);
        } catch (TimeoutException e) {
            report.cancel(true);
            return Response.error(504, "the count took longer than this server waits ("
                    + limits.countTimeout().toSeconds() + " s); count it with the command line instead");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.error(503, "the server is stopping");
        } catch (ExecutionException e) {
            return refused(e.getCause());
        }
    }

    /** Why a count failed, as the command line sorts it: the input's fault (400) or this program's (500). */
    private static Response refused(Throwable cause) {
        if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException
                || cause instanceof UnsupportedOperationException || cause instanceof UncheckedIOException
                || cause instanceof BallotParseException) {
            return Response.error(400, fieldNames(cause.getMessage()));
        }
        if (cause instanceof OutOfMemoryError) {
            return Response.error(503, "the count ran out of memory; count it with the command line instead");
        }
        return Response.error(500, "an internal fault, please report it: " + cause);
    }

    private static String countByTheLibrary(CountRequest request, ReportFormat format, List<String> warnings) {
        Tabulator tabulator = Tabulator.of(new CountOptions(request.method(), request.ruleset(), request.seats(),
                request.quota(), request.transfer(), request.rounding(), request.tiebreak(), request.tiedRankings(),
                request.minimumPreferences(), request.precision(), request.bordaScoring()), null);
        Election election = Election.parse(request.ballots(), request.format());
        return tabulator.count(election, warning -> {
            synchronized (warnings) {
                warnings.add(warning);
            }
        }).report(format);
    }

    private Response page(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            return notAllowed("GET");
        }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        if (!STATIC_PATH.matcher(path).matches()) {
            return Response.error(404, "no such page: " + path);
        }
        try (InputStream in = DemoServer.class.getResourceAsStream("/static" + path)) {
            if (in == null) {
                return Response.error(404, "no such page: " + path);
            }
            String extension = path.substring(path.lastIndexOf('.') + 1);
            String type = switch (extension) {
                case "html" -> "text/html";
                case "css" -> "text/css";
                case "js" -> "text/javascript";
                case "blt", "csv", "txt" -> "text/plain";
                default -> "application/octet-stream";
            };
            return new Response(200, type, new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.of());
        }
    }

    /** The request's body, or null where it's larger than the limit. */
    private byte[] readBody(HttpExchange exchange) throws IOException {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null && length.matches("\\d{1,18}") && Long.parseLong(length) > limits.maxRequestBytes()) {
            return null;
        }
        byte[] body = exchange.getRequestBody().readNBytes(limits.maxRequestBytes() + 1);
        return body.length > limits.maxRequestBytes() ? null : body;
    }

    private static Response notAllowed(String allowed) {
        Response refused = Response.error(405, "use " + allowed + " here");
        return new Response(refused.status(), refused.contentType(), refused.body(), Map.of("Allow", allowed));
    }

    private static String contentType(ReportFormat format) {
        return switch (format) {
            case TEXT -> "text/plain";
            case CSV -> "text/csv";
            case JSON -> "application/json";
            case HTML -> "text/html";
        };
    }

    /** A message naming options as the request does: {@code --tied-rankings} becomes {@code tiedRankings}. */
    static String fieldNames(String message) {
        if (message == null) {
            return "";
        }
        Matcher flag = FLAG.matcher(message);
        StringBuilder named = new StringBuilder();
        while (flag.find()) {
            flag.appendReplacement(named, Matcher.quoteReplacement(fieldName(flag.group())));
        }
        return flag.appendTail(named).toString();
    }

    private static String fieldName(String flag) {
        String[] words = flag.substring(2).split("-");
        StringBuilder name = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            name.append(words[i].substring(0, 1).toUpperCase(Locale.ROOT)).append(words[i].substring(1));
        }
        return name.toString();
    }
}
