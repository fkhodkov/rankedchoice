package info.fkhodkov.rankedchoice.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

/**
 * Every method, in every report format, on elections at the edges: no ballots, only blank ones,
 * every candidate withdrawn or all but one, as many seats as candidates or more, a single
 * candidate, a tie, a count too large for a long, and the like. Each run must end in a report or
 * in one clear error line, the same in every format: never an internal fault ({@link Main} names
 * any exception the count doesn't throw on purpose), a stack trace, "Error: null", invalid JSON or
 * a "null" in a report. Sweeps like this found CPO-STV's single-outcome reports failing, an index
 * error in the Schulze proportional ranking, overflowing totals in Meek and Cambridge STV, and
 * Schulze and Borda reporting nothing with every candidate withdrawn; this keeps them found. It
 * doesn't check the figures: a wrong count that reports cleanly (Borda's withdrawn candidates
 * once scored below zero) is for each method's own tests.
 */
class EdgeInputsTest {

    private static final String NAMES = "\"A\"\n\"B\"\n\"C\"\n\"t\"\n";

    private static final Map<String, String> ELECTIONS = Map.ofEntries(
            Map.entry("normal", "4 2\n5 1 2 3 0\n4 2 3 0\n3 3 1 0\n2 4 0\n60 1 0\n55 2 0\n0\n"
                    + "\"A\"\n\"B\"\n\"C\"\n\"D\"\n\"t\"\n"),
            Map.entry("as many seats as candidates", "3 3\n5 1 2 0\n3 2 0\n0\n" + NAMES),
            Map.entry("more seats than candidates", "3 4\n5 1 2 0\n3 2 0\n0\n" + NAMES),
            Map.entry("only blank ballots", "3 1\n5 0\n3 0\n0\n" + NAMES),
            Map.entry("no ballots", "3 1\n0\n" + NAMES),
            Map.entry("a candidate nobody ranks", "3 1\n5 1 0\n3 2 0\n0\n" + NAMES),
            Map.entry("every candidate withdrawn", "3 1\n-1 -2 -3\n5 1 2 0\n3 2 3 0\n0\n" + NAMES),
            Map.entry("one candidate left", "3 1\n-1 -2\n5 1 2 0\n3 2 3 0\n0\n" + NAMES),
            Map.entry("one candidate", "1 1\n5 1 0\n0\n\"A\"\n\"t\"\n"),
            Map.entry("tied rankings", "3 1\n5 1=2 3 0\n3 2 0\n4 3 0\n0\n" + NAMES),
            Map.entry("a count past long", "3 1\n99999999999999999999999 1 2 3 0\n5 2 3 0\n0\n" + NAMES),
            Map.entry("two counts summing past long",
                    "3 1\n5000000000000000000 1 2 3 0\n5000000000000000000 2 3 0\n4 3 1 0\n0\n" + NAMES),
            Map.entry("a byte order mark", "﻿3 1\n5 1 2 0\n3 2 0\n0\n" + NAMES));

    private static final List<String> METHODS = List.of("stv", "meek", "warren", "cambridge", "schulze", "borda",
            "cpo-stv", "schulze-stv", "schulze-proportional", "qpq");

    private static final List<String> FORMATS = List.of("text", "csv", "json", "html");

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private PrintStream originalOut;
    private PrintStream originalErr;
    private ByteArrayOutputStream capturedOut;
    private ByteArrayOutputStream capturedErr;

    @BeforeEach
    void captureStreams() {
        originalOut = System.out;
        originalErr = System.err;
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    static Stream<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        for (String election : ELECTIONS.keySet().stream().sorted().toList()) {
            for (String method : METHODS) {
                cases.add(Arguments.of(election, method));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{1} on {0}")
    @MethodSource("cases")
    void endsInAReportOrOneClearErrorInEveryFormat(String election, String method) throws Exception {
        Path ballots = tempDir.resolve("election.blt");
        Files.writeString(ballots, ELECTIONS.get(election));

        Integer firstExit = null;
        String firstError = null;
        for (String format : FORMATS) {
            int exit = run("--input", ballots.toString(), "--method", method, "--report-format", format);
            String out = capturedOut.toString(StandardCharsets.UTF_8);
            String err = capturedErr.toString(StandardCharsets.UTF_8);
            String context = method + " on " + election + " as " + format;

            assertThat(exit).as(context + ": exit code").isIn(0, 1);
            assertThat(out + err).as(context).doesNotContain("Exception").doesNotContain("\tat ");
            if (exit == 1) {
                assertThat(out).as(context + ": no report").isEmpty();
                assertThat(err.lines().toList()).as(context + ": one error line").singleElement().asString()
                        .startsWith("Error: ").isNotEqualTo("Error: null").doesNotContain("internal fault")
                        .doesNotContain("java.").doesNotContain("info.fkhodkov");
            } else {
                assertThat(out).as(context + ": a report").isNotBlank();
                if (format.equals("json")) {
                    assertThatCode(() -> JSON.readTree(out)).as(context + ": valid JSON").doesNotThrowAnyException();
                } else {
                    assertThat(out).as(context).doesNotContain("null").doesNotContain("NaN");
                }
                if (format.equals("html")) {
                    assertThat(out).as(context).contains("</html>");
                }
            }
            if (firstExit == null) {
                firstExit = exit;
                firstError = err;
            } else {
                assertThat(exit).as(context + ": the same outcome as text").isEqualTo(firstExit);
                if (exit == 1) {
                    assertThat(err).as(context + ": the same error as text").isEqualTo(firstError);
                }
            }
        }
    }

    private int run(String... args) {
        capturedOut = new ByteArrayOutputStream();
        capturedErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
        return new CommandLine(new Main()).execute(args);
    }
}
