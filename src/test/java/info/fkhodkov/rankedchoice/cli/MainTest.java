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
}
