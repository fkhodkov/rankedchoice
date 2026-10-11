package info.fkhodkov.rankedchoice.count;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.CsvBallotParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedBallots;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/**
 * An election as a count takes it: the candidates standing (a BLT file's withdrawn candidates left
 * out), the ballots, and the seats the file itself gives, or null where it gives none (CSV).
 */
public record Election(List<Candidate> candidates, List<Ballot> ballots, Integer seats) {

    /** Reads a ballot file, as {@code blt} or {@code csv}; a null format is inferred from the file's name. */
    public static Election read(Path path, String format) {
        String resolved = format != null ? format.toLowerCase(Locale.ROOT) : inferFormat(path);
        return switch (resolved) {
            case "blt" -> of(BltParser.parse(path));
            case "csv" -> of(CsvBallotParser.parse(path));
            default -> throw new IllegalArgumentException(
                    "Unsupported format '" + resolved + "'; expected 'blt' or 'csv'");
        };
    }

    /**
     * Reads ballots given as text, as {@code blt} or {@code csv}. A null format is taken from the
     * text: CSV when its first non-blank line starts with a {@code count} column, BLT otherwise.
     */
    public static Election parse(String text, String format) {
        String resolved = format != null ? format.toLowerCase(Locale.ROOT) : sniffFormat(text);
        return switch (resolved) {
            case "blt" -> of(BltParser.parse(text.lines()));
            case "csv" -> of(CsvBallotParser.parse(text.lines()));
            default -> throw new IllegalArgumentException(
                    "Unsupported format '" + resolved + "'; expected 'blt' or 'csv'");
        };
    }

    public static Election of(ParsedElection election) {
        List<Candidate> standing = election.candidates().stream()
                .filter(candidate -> !election.withdrawnCandidates().contains(candidate))
                .toList();
        return new Election(standing, election.ballots(), election.seats());
    }

    public static Election of(ParsedBallots ballots) {
        return new Election(ballots.candidates(), ballots.ballots(), null);
    }

    private static String sniffFormat(String text) {
        String first = text.lines().map(String::strip)
                .map(line -> line.startsWith("\uFEFF") ? line.substring(1) : line)
                .filter(line -> !line.isEmpty())
                .findFirst().orElse("");
        String head = first.toLowerCase(Locale.ROOT);
        return head.equals("count") || head.startsWith("count,") || head.startsWith("\"count\"") ? "csv" : "blt";
    }

    private static String inferFormat(Path path) {
        Path name = path.getFileName();
        String lower = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".blt")) {
            return "blt";
        }
        if (lower.endsWith(".csv")) {
            return "csv";
        }
        throw new IllegalArgumentException(
                "Cannot infer format from file name '" + path + "'; pass --format explicitly");
    }
}
