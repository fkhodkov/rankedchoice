package info.fkhodkov.rankedchoice.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.CsvBallotParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedBallots;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.report.StvTextReport;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Tabulates an STV election from a ballot file. Only Droop quota + fractional (Gregory)
 * transfer is available so far — quota/transfer/tie-break choices become CLI options once
 * more variants exist.
 */
@Command(name = "rankedchoice", mixinStandardHelpOptions = true,
        description = "Tabulate a single transferable vote election from a ballot file.")
public final class Main implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, required = true, description = "Ballot file (.blt or .csv)")
    private Path input;

    @Option(names = {"-f", "--format"}, description = "Input format: blt or csv. Inferred from the file extension if omitted.")
    private String format;

    @Option(names = {"-s", "--seats"},
            description = "Seats to fill. Required for CSV input; for BLT input, overrides the file's own seat count if given.")
    private Integer seats;

    @Option(names = {"-o", "--output"}, description = "Output file. Defaults to standard output.")
    private Path output;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() {
        try {
            String report = runCount();
            if (output != null) {
                Files.writeString(output, report);
            } else {
                System.out.print(report);
            }
            return 0;
        } catch (IOException e) {
            System.err.println("Error: failed to read or write a file: " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private String runCount() throws IOException {
        String resolvedFormat = format != null ? format.toLowerCase(Locale.ROOT) : inferFormat(input);

        List<Candidate> candidates;
        List<Ballot> ballots;
        int effectiveSeats;

        switch (resolvedFormat) {
            case "blt" -> {
                ParsedElection election = BltParser.parse(input);
                candidates = election.candidates().stream()
                        .filter(candidate -> !election.withdrawnCandidates().contains(candidate))
                        .toList();
                ballots = election.ballots();
                effectiveSeats = seats != null ? seats : election.seats();
            }
            case "csv" -> {
                if (seats == null) {
                    throw new IllegalArgumentException("--seats is required for CSV input");
                }
                ParsedBallots parsed = CsvBallotParser.parse(input);
                candidates = parsed.candidates();
                ballots = parsed.ballots();
                effectiveSeats = seats;
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported format '" + resolvedFormat + "'; expected 'blt' or 'csv'");
        }

        StvRuleset ruleset = new StvRuleset(
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, new DeclaredOrderTieBreak(candidates));
        StvResult result = StvCounter.count(candidates, ballots, effectiveSeats, ruleset);
        return StvTextReport.render(result);
    }

    private static String inferFormat(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".blt")) {
            return "blt";
        }
        if (name.endsWith(".csv")) {
            return "csv";
        }
        throw new IllegalArgumentException(
                "Cannot infer format from file name '" + path.getFileName() + "'; pass --format explicitly");
    }
}
