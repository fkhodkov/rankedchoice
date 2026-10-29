package info.fkhodkov.rankedchoice.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.count.CountOptions;
import info.fkhodkov.rankedchoice.count.Election;
import info.fkhodkov.rankedchoice.count.ReportFormat;
import info.fkhodkov.rankedchoice.count.Tabulator;

class DemoServerTest {

    /** Wikipedia's "Andrea, Brad, Carter, Delilah" example, as in the library's fixtures. */
    private static final String WIKIPEDIA = """
            4 2
            24 1 2 0
            16 1 3 2 0
            17 4 0
            0
            "Andrea"
            "Brad"
            "Carter"
            "Delilah"
            "Andrea, Brad, Carter, Delilah"
            """;

    private static final String CSV = """
            count,rank1,rank2,rank3
            4,Alice,Bob,Carol
            3,Bob,Carol,
            2,Carol,Alice,
            """;

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private DemoServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private void start(Limits limits) throws IOException {
        server = DemoServer.start(new InetSocketAddress("127.0.0.1", 0), limits);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> count(Map<String, Object> request) throws Exception {
        return post("/api/count", JSON.writeValueAsString(request));
    }

    private static String error(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body()).get("error").asText();
    }

    @Test
    void listsTheMethodsWithTheOptionsEachTakesAndTheRulesets() throws Exception {
        start(Limits.DEFAULT);

        HttpResponse<String> response = get("/api/methods");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode answer = JSON.readTree(response.body());
        assertThat(answer.get("methods")).hasSize(10);
        JsonNode stv = answer.get("methods").get(0);
        assertThat(stv.get("name").asText()).isEqualTo("stv");
        assertThat(stv.get("options").toString()).contains("\"seats\"", "\"quota\"", "\"tiedRankings\"")
                .doesNotContain("precision", "bordaScoring");
        JsonNode schulze = answer.get("methods").findParents("name").stream()
                .filter(method -> method.get("name").asText().equals("schulze")).findFirst().orElseThrow();
        assertThat(schulze.get("options")).isEmpty();
        assertThat(answer.get("rulesets").toString()).contains("\"scottish\"", "\"ers97\"");
        JsonNode ers97 = answer.get("rulesets").findParents("name").stream()
                .filter(ruleset -> ruleset.get("name").asText().equals("ers97")).findFirst().orElseThrow();
        assertThat(ers97.get("settings").get("quota").asText()).isEqualTo("droop-rounded:2");
        assertThat(ers97.get("settings").get("batchExclusion").asBoolean()).isTrue();
        assertThat(ers97.get("settings").has("seats")).isFalse();
        assertThat(answer.get("limits").get("countTimeoutSeconds").asInt()).isEqualTo(30);
    }

    @Test
    void countsAsTheLibraryDoesInTheFormatAskedFor() throws Exception {
        start(Limits.DEFAULT);
        String expected = Tabulator.of(CountOptions.NONE, null).count(Election.parse(WIKIPEDIA, "blt"), w -> { })
                .report(ReportFormat.TEXT);

        HttpResponse<String> text = count(Map.of("ballots", WIKIPEDIA));
        HttpResponse<String> html = count(Map.of("ballots", WIKIPEDIA, "ruleset", "scottish", "reportFormat", "html"));

        assertThat(text.statusCode()).isEqualTo(200);
        assertThat(text.headers().firstValue("Content-Type")).hasValue("text/plain; charset=utf-8");
        assertThat(text.body()).isEqualTo(expected).contains("Elected: Andrea, Brad");
        assertThat(html.statusCode()).isEqualTo(200);
        assertThat(html.body()).startsWith("<!DOCTYPE html>").contains("20.00000");
        assertThat(html.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(
                policy -> assertThat(policy).startsWith("default-src 'none'"));
    }

    @Test
    void takesAGivenListAsAListOfNames() throws Exception {
        start(Limits.DEFAULT);

        HttpResponse<String> response = count(Map.of("ballots", WIKIPEDIA, "method", "schulze-proportional",
                "givenList", List.of("Delilah", "Carter")));
        HttpResponse<String> refused = count(Map.of("ballots", WIKIPEDIA, "givenList", List.of("Delilah")));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("1. Delilah  (given; potential winners: Andrea)");
        assertThat(error(refused)).isEqualTo("givenList doesn't apply to method stv: only schulze-proportional "
                + "uses it");
    }

    @Test
    void takesCsvFromItsHeader() throws Exception {
        start(Limits.DEFAULT);

        HttpResponse<String> stv = count(Map.of("ballots", CSV, "seats", 1));
        HttpResponse<String> borda = count(Map.of("ballots", CSV, "method", "borda", "reportFormat", "json"));

        assertThat(stv.statusCode()).isEqualTo(200);
        assertThat(stv.body()).contains("Elected: Alice");
        assertThat(borda.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(borda.body()).get("ranking").get(0).get("candidate").asText()).isEqualTo("Alice");
    }

    @Test
    void refusesACountAsTheCommandLineDoesNamingOptionsAsTheRequestDoes() throws Exception {
        start(Limits.DEFAULT);

        HttpResponse<String> seats = count(Map.of("ballots", WIKIPEDIA, "method", "schulze", "seats", 2));
        HttpResponse<String> csv = count(Map.of("ballots", CSV));
        HttpResponse<String> tied = count(Map.of("ballots", WIKIPEDIA, "method", "borda", "tiedRankings", "skip"));
        HttpResponse<String> broken = count(Map.of("ballots", "4 2\n1 9 0\n0\n"));

        assertThat(seats.statusCode()).isEqualTo(400);
        assertThat(error(seats)).isEqualTo(
                "seats doesn't apply to method schulze: it ranks every candidate rather than filling seats");
        assertThat(error(csv)).isEqualTo("seats is required for CSV input");
        assertThat(error(tied)).startsWith("tiedRankings doesn't apply to method borda");
        assertThat(broken.statusCode()).isEqualTo(400);
        assertThat(error(broken)).contains("candidate id out of range: 9");
    }

    @Test
    void refusesAFractionWhereAWholeNumberBelongs() throws Exception {
        start(Limits.DEFAULT);

        HttpResponse<String> seats = count(Map.of("ballots", WIKIPEDIA, "seats", 1.9));
        HttpResponse<String> minimum = count(Map.of("ballots", WIKIPEDIA, "minimumPreferences", 2.8));
        HttpResponse<String> whole = count(Map.of("ballots", WIKIPEDIA, "seats", 1));

        assertThat(seats.statusCode()).isEqualTo(400);
        assertThat(error(seats)).isEqualTo("seats must be a whole number, not 1.9");
        assertThat(minimum.statusCode()).isEqualTo(400);
        assertThat(error(minimum)).isEqualTo("minimumPreferences must be a whole number, not 2.8");
        assertThat(whole.statusCode()).isEqualTo(200);
    }

    @Test
    void refusesARequestThatIsNotACount() throws Exception {
        start(new Limits(1000, 1, Duration.ofSeconds(30)));

        assertThat(error(post("/api/count", "not json"))).startsWith("the request isn't a count as JSON");
        assertThat(error(count(Map.of("ballots", WIKIPEDIA, "seat", 2)))).contains("seat");
        assertThat(error(count(Map.of("method", "stv")))).startsWith("ballots is required");
        assertThat(error(count(Map.of("ballots", WIKIPEDIA, "reportFormat", "pdf"))))
                .startsWith("Unsupported report format 'pdf'");
        HttpResponse<String> large = count(Map.of("ballots", "1 1 0\n".repeat(300)));
        assertThat(large.statusCode()).isEqualTo(413);
        assertThat(get("/api/count").statusCode()).isEqualTo(405);
        assertThat(get("/api/count").headers().firstValue("Allow")).hasValue("POST");
    }

    @Test
    void turnsCountsAwayWhileEveryPlaceIsTakenAndStopsWaitingAtTheLimit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = DemoServer.start(new InetSocketAddress("127.0.0.1", 0), new Limits(1000, 1, Duration.ofMillis(300)),
                (request, format, warnings) -> {
                    if (request.method() != null && request.method().equals("slow")) {
                        // Deaf to the interrupt the server sends when it stops waiting, so it keeps its place.
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        while (release.getCount() > 0 && System.nanoTime() < deadline) {
                            Thread.onSpinWait();
                        }
                    }
                    if (request.method() != null && request.method().equals("warn")) {
                        warnings.add("figures grew\nlong");
                        warnings.add("again");
                    }
                    return "counted";
                });

        HttpResponse<String> slow = count(Map.of("ballots", "x", "method", "slow"));
        HttpResponse<String> busy = count(Map.of("ballots", "x"));
        release.countDown();
        Thread.sleep(200);
        HttpResponse<String> after = count(Map.of("ballots", "x"));

        assertThat(slow.statusCode()).isEqualTo(504);
        assertThat(error(slow)).startsWith("the count took longer than this server waits");
        assertThat(busy.statusCode()).isEqualTo(503);
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(after.body()).isEqualTo("counted");
        assertThat(after.headers().firstValue("X-Count-Warning")).isEmpty();
        assertThat(count(Map.of("ballots", "x", "method", "warn")).headers().firstValue("X-Count-Warning"))
                .hasValue("figures grew long | again");
    }

    @Test
    void aCountPastItsTimeStopsAndGivesUpItsPlace() throws Exception {
        start(new Limits(1024 * 1024, 1, Duration.ofMillis(300)));
        // CPO-STV, 12 candidates for 4 seats: 495 committees, every pair counted out; on 5,000 ballots
        // that takes many seconds, and before counts heeded interrupts, it kept the only place all along.
        Random random = new Random(20261029);
        StringBuilder ballots = new StringBuilder("12 4\n");
        List<Integer> order = new ArrayList<>(IntStream.rangeClosed(1, 12).boxed().toList());
        for (int b = 0; b < 5000; b++) {
            Collections.shuffle(order, random);
            ballots.append("1 ");
            order.subList(0, 1 + random.nextInt(12)).forEach(c -> ballots.append(c).append(' '));
            ballots.append("0\n");
        }
        ballots.append("0\n");
        IntStream.rangeClosed(1, 12).forEach(c -> ballots.append("\"C").append(c).append("\"\n"));
        ballots.append("\"Twelve for four\"\n");

        HttpResponse<String> slow = count(Map.of("ballots", ballots.toString(), "method", "cpo-stv"));
        assertThat(slow.statusCode()).isEqualTo(504);
        // The place comes free as soon as the count notices it was stopped.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        HttpResponse<String> next;
        do {
            Thread.sleep(50);
            next = count(Map.of("ballots", WIKIPEDIA));
        } while (next.statusCode() == 503 && System.nanoTime() < deadline);
        assertThat(next.statusCode()).isEqualTo(200);
        // And none of its work is still running on the common pool, nor waiting there; a worker
        // that has just finished may take a moment to count as idle.
        ForkJoinPool pool = ForkJoinPool.commonPool();
        long settled = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
        while ((pool.getActiveThreadCount() > 0 || pool.getQueuedTaskCount() > 0) && System.nanoTime() < settled) {
            Thread.sleep(10);
        }
        assertThat(pool.getQueuedTaskCount()).isZero();
        assertThat(pool.getActiveThreadCount()).isZero();
    }

    @Test
    void servesThePageAndNothingOutsideIt() throws Exception {
        start(Limits.DEFAULT);

        HttpResponse<String> page = get("/");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).hasValue("text/html; charset=utf-8");
        assertThat(page.body()).contains("<form id=\"count-form\"", "/app.js", "/style.css");
        assertThat(get("/app.js").headers().firstValue("Content-Type")).hasValue("text/javascript; charset=utf-8");
        assertThat(get("/app.js").body()).contains("/api/count", "/api/methods", "/examples/examples.json");
        assertThat(get("/style.css").statusCode()).isEqualTo(200);
        assertThat(get("/nothing.html").statusCode()).isEqualTo(404);
        assertThat(get("/../pom.xml").statusCode()).isEqualTo(404);
        assertThat(get("/static/index.html").statusCode()).isEqualTo(404);
        assertThat(get("/info/fkhodkov/rankedchoice/server/DemoServer.class").statusCode()).isEqualTo(404);
    }

    /**
     * Every example the page offers is served, counts with the options it's offered with, and, where
     * it names the library fixture it's a copy of, is still that fixture byte for byte (decompressed).
     */
    @Test
    void everyExampleIsServedAndCountsAndMatchesItsFixture() throws Exception {
        start(Limits.DEFAULT);
        HttpResponse<String> manifest = get("/examples/examples.json");
        assertThat(manifest.headers().firstValue("Content-Type")).hasValue("application/json; charset=utf-8");
        JsonNode examples = JSON.readTree(manifest.body());
        assertThat(examples).hasSizeGreaterThanOrEqualTo(22);
        Path fixtures = Path.of("../library/src/test/resources/testdata");
        Set<String> methods = new HashSet<>();
        Set<String> rulesets = new HashSet<>();
        int copies = 0;

        for (JsonNode example : examples) {
            String file = example.get("file").asText();
            HttpResponse<String> ballots = get("/examples/" + file);
            assertThat(ballots.statusCode()).as(file).isEqualTo(200);
            if (example.has("fixture")) {
                Path fixture = fixtures.resolve(example.get("fixture").asText());
                byte[] bytes = Files.readAllBytes(fixture);
                if (fixture.toString().endsWith(".gz")) {
                    try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                        bytes = in.readAllBytes();
                    }
                }
                assertThat(ballots.body()).as(file).isEqualTo(new String(bytes, StandardCharsets.UTF_8));
                copies++;
            }
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("ballots", ballots.body());
            request.put("reportFormat", "html");
            example.get("options").fields().forEachRemaining(option -> request.put(option.getKey(),
                    option.getValue().isNumber() ? option.getValue().asInt() : option.getValue().asText()));
            HttpResponse<String> counted = count(request);
            assertThat(counted.statusCode()).as(file + ": " + counted.body()).isEqualTo(200);
            methods.add(example.get("options").get("method").asText());
            if (example.get("options").has("ruleset")) {
                rulesets.add(example.get("options").get("ruleset").asText());
            }
            assertThat(example.get("title").asText()).isNotBlank();
            assertThat(example.get("about").asText()).isNotBlank();
            assertThat(example.get("group").asText()).isNotBlank();
        }
        assertThat(copies).isEqualTo(examples.size() - 1);
        // An example for every method the server counts by, and for every ruleset but two: Tasmania
        // publishes no ballots for hare-clark-qpq, and ers76 has the same settings as ers97.
        JsonNode api = JSON.readTree(get("/api/methods").body());
        assertThat(methods).containsExactlyInAnyOrderElementsOf(api.get("methods").findValuesAsText("name"));
        Set<String> withoutExample = new HashSet<>(api.get("rulesets").findValuesAsText("name"));
        withoutExample.removeAll(rulesets);
        assertThat(withoutExample).containsExactlyInAnyOrder("hare-clark-qpq", "ers76");
    }

    @Test
    void namesOptionsAsTheRequestDoes() {
        assertThat(DemoServer.fieldNames("--minimum-preferences doesn't apply to --method schulze"))
                .isEqualTo("minimumPreferences doesn't apply to method schulze");
        assertThat(DemoServer.fieldNames("a quota of 2--3 votes")).isEqualTo("a quota of 2--3 votes");
    }
}
