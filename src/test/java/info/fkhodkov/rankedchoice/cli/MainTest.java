package info.fkhodkov.rankedchoice.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

class MainTest {

    private static final String BLT = """
            3 1
            5 1 0
            3 2 1 0
            3 3 1 0
            0
            "X"
            "Y"
            "Z"
            "Test Election"
            """;

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
        capturedOut = new ByteArrayOutputStream();
        capturedErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void tabulatesBltFileToStdout() throws Exception {
        Path bltFile = tempDir.resolve("election.blt");
        Files.writeString(bltFile, BLT);

        int exitCode = new CommandLine(new Main()).execute("--input", bltFile.toString());

        assertThat(exitCode).isZero();
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Elected: X");
    }

    @Test
    void writesReportToOutputFileWhenGiven() throws Exception {
        Path bltFile = tempDir.resolve("election.blt");
        Files.writeString(bltFile, BLT);
        Path outputFile = tempDir.resolve("report.txt");

        int exitCode = new CommandLine(new Main())
                .execute("--input", bltFile.toString(), "--output", outputFile.toString());

        assertThat(exitCode).isZero();
        assertThat(Files.readString(outputFile)).contains("Elected: X");
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void tabulatesCsvFileWithExplicitSeats() throws Exception {
        String csv = """
                count,rank1,rank2
                5,X,
                3,Y,X
                3,Z,X
                """;
        Path csvFile = tempDir.resolve("election.csv");
        Files.writeString(csvFile, csv);

        int exitCode = new CommandLine(new Main())
                .execute("--input", csvFile.toString(), "--seats", "1");

        assertThat(exitCode).isZero();
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Elected: X");
    }

    @Test
    void csvWithoutSeatsFails() throws Exception {
        Path csvFile = tempDir.resolve("election.csv");
        Files.writeString(csvFile, "count,rank1\n1,X\n");

        int exitCode = new CommandLine(new Main()).execute("--input", csvFile.toString());

        assertThat(exitCode).isOne();
        assertThat(capturedErr.toString(StandardCharsets.UTF_8)).contains("--seats is required");
    }

    @Test
    void unrecognizedExtensionWithoutFormatFails() throws Exception {
        Path oddFile = tempDir.resolve("election.txt");
        Files.writeString(oddFile, "count,rank1\n1,X\n");

        int exitCode = new CommandLine(new Main()).execute("--input", oddFile.toString());

        assertThat(exitCode).isOne();
        assertThat(capturedErr.toString(StandardCharsets.UTF_8)).contains("Cannot infer format");
    }

    /**
     * hare-clark-qpq elects Z, not X: X's 5 ballots rank only X, and Tasmanian law requires at
     * least 2 preferences with 3 candidates (Electoral Act 2004 s.102(2), via the preset's
     * minimumPreferences), so those papers are informal.
     */
    @ParameterizedTest
    @CsvSource({"scottish, X", "ers97, X", "ers76, X", "hare-clark-qpq, Z", "act-hare-clark, X"})
    void bundledRulesetRunsSuccessfully(String rulesetName, String expectedWinner) throws Exception {
        Path bltFile = tempDir.resolve("election.blt");
        Files.writeString(bltFile, BLT);

        int exitCode = new CommandLine(new Main())
                .execute("--input", bltFile.toString(), "--ruleset", rulesetName);

        assertThat(exitCode).isZero();
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Elected: " + expectedWinner);
    }

    @Test
    void tiedRankingsFlagIsRefusedForAMethodThatCountsTiesNatively() throws Exception {
        Path bltFile = tempDir.resolve("election.blt");
        Files.writeString(bltFile, BLT);

        int exitCode = new CommandLine(new Main())
                .execute("--input", bltFile.toString(), "--method", "schulze", "--tied-rankings", "skip");

        assertThat(exitCode).isEqualTo(1);
        assertThat(capturedErr.toString(StandardCharsets.UTF_8))
                .contains("--tied-rankings doesn't apply to --method schulze");
    }

    /** Lifting hare-clark-qpq's minimum makes X's 5 single-preference ballots formal again, so X wins. */
    @Test
    void minimumPreferencesFlagOverridesThePreset() throws Exception {
        Path bltFile = tempDir.resolve("election.blt");
        Files.writeString(bltFile, BLT);

        int exitCode = new CommandLine(new Main()).execute(
                "--input", bltFile.toString(), "--ruleset", "hare-clark-qpq", "--minimum-preferences", "0");

        assertThat(exitCode).isZero();
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Elected: X");
    }

    /**
     * X's 5 single-preference ballots become informal, leaving 6 valid votes: quota 6/2 = 3 (plus
     * Meek's smallest fixed-point step), not 11/2.
     */
    @Test
    void minimumPreferencesFlagMakesShortBallotsInformalForMeek() throws Exception {
        Path bltFile = tempDir.resolve("election.blt");
        Files.writeString(bltFile, BLT);

        int exitCode = new CommandLine(new Main()).execute(
                "--input", bltFile.toString(), "--method", "meek", "--minimum-preferences", "2");

        assertThat(exitCode).isZero();
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Quota: 3.000000001\n").contains("X: 0");
    }

    @ParameterizedTest
    @CsvSource({"qpq", "cpo-stv"})
    void qpqAndCpoStvReportsShowInformalBallots(String method) throws Exception {
        Path bltFile = tempDir.resolve("election.blt");
        Files.writeString(bltFile, BLT);

        int exitCode = new CommandLine(new Main()).execute(
                "--input", bltFile.toString(), "--method", method, "--minimum-preferences", "2");

        assertThat(exitCode).isZero();
        assertThat(capturedOut.toString(StandardCharsets.UTF_8))
                .startsWith("Informal ballots: 5 (too few preferences: 5)\n\n");
    }

    @Test
    void minimumPreferencesFlagIsRefusedForAMethodWithoutIt() throws Exception {
        Path bltFile = tempDir.resolve("election.blt");
        Files.writeString(bltFile, BLT);

        int exitCode = new CommandLine(new Main())
                .execute("--input", bltFile.toString(), "--method", "borda", "--minimum-preferences", "2");

        assertThat(exitCode).isEqualTo(1);
        assertThat(capturedErr.toString(StandardCharsets.UTF_8))
                .contains("--minimum-preferences doesn't apply to --method borda");
    }

    @Test
    void unknownRulesetFails() throws Exception {
        Path bltFile = tempDir.resolve("election.blt");
        Files.writeString(bltFile, BLT);

        int exitCode = new CommandLine(new Main())
                .execute("--input", bltFile.toString(), "--ruleset", "does-not-exist");

        assertThat(exitCode).isOne();
        assertThat(capturedErr.toString(StandardCharsets.UTF_8))
                .contains("does-not-exist")
                .contains("scottish")
                .contains("ers97")
                .contains("ers76")
                .contains("hare-clark-qpq")
                .contains("act-hare-clark");
    }

    @Test
    void configAndRulesetAreMutuallyExclusive() throws Exception {
        Path bltFile = tempDir.resolve("election.blt");
        Files.writeString(bltFile, BLT);
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "quota: hare\n");

        int exitCode = new CommandLine(new Main()).execute(
                "--input", bltFile.toString(), "--config", configFile.toString(), "--ruleset", "scottish");

        assertThat(exitCode).isOne();
        assertThat(capturedErr.toString(StandardCharsets.UTF_8)).contains("mutually exclusive");
    }

    @Test
    void configFileChangesTheQuota() throws Exception {
        // 11 votes, 1 seat: Droop = floor(11/2)+1 = 6; Hare = 11/1 = 11.
        Path bltFile = tempDir.resolve("election.blt");
        Files.writeString(bltFile, BLT);
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "quota: hare\n");

        int exitCode = new CommandLine(new Main())
                .execute("--input", bltFile.toString(), "--config", configFile.toString());

        assertThat(exitCode).isZero();
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Quota: 11");
    }

    @Test
    void quotaFlagOverridesConfigFile() throws Exception {
        Path bltFile = tempDir.resolve("election.blt");
        Files.writeString(bltFile, BLT);
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "quota: hare\n");

        int exitCode = new CommandLine(new Main()).execute(
                "--input", bltFile.toString(), "--config", configFile.toString(), "--quota", "droop");

        assertThat(exitCode).isZero();
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Quota: 6");
    }

    @Test
    void seatsCanComeFromConfigFileForCsvInput() throws Exception {
        String csv = """
                count,rank1,rank2
                5,X,
                3,Y,X
                3,Z,X
                """;
        Path csvFile = tempDir.resolve("election.csv");
        Files.writeString(csvFile, csv);
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, "seats: 1\n");

        int exitCode = new CommandLine(new Main())
                .execute("--input", csvFile.toString(), "--config", configFile.toString());

        assertThat(exitCode).isZero();
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).contains("Elected: X");
    }
}
