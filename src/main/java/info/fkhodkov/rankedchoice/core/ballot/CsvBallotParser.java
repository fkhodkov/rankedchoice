package info.fkhodkov.rankedchoice.core.ballot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/**
 * Parses a simple CSV ballot format: a header row whose first column is {@code count}
 * (case-insensitive) followed by one column per preference rank, e.g.:
 *
 * <pre>
 * count,rank1,rank2,rank3
 * 42,Alice,Bob,
 * 7,Bob,Alice=Carol,
 * </pre>
 *
 * A blank cell ends that ballot's ranking (later cells in the row must also be blank).
 * Tied candidates within a single rank are written as {@code A=B}. Candidate identity is
 * the trimmed cell text itself. Cells may be double-quoted (RFC4180-style, {@code ""} as an
 * escaped quote) to contain a literal comma or {@code =}.
 */
public final class CsvBallotParser {

    private CsvBallotParser() {
    }

    public static ParsedBallots parse(Path path) {
        try(Stream<String> lines = Files.lines(path)) {
            return parse(lines);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read CSV file: " + path, e);
        }
    }

    public static ParsedBallots parse(Stream<String> lines) {
        Iterator<NumberedLine> cursor = NumberedLine.number(lines)
                .filter(line -> !line.text().isBlank())
                .iterator();
        if (!cursor.hasNext()) {
            throw new BallotParseException("CSV input is empty");
        }

        NumberedLine headerLine = cursor.next();
        List<String> header = splitCsvLine(headerLine.text());
        if (header.isEmpty() || !header.getFirst().trim().equalsIgnoreCase("count")) {
            throw new BallotParseException(
                    "Line " + headerLine.number() + ": CSV header must start with a 'count' column, got: '"
                            + headerLine.text() + "'");
        }

        Map<String, Candidate> candidatesByName = new LinkedHashMap<>();
        List<Ballot> ballots = new ArrayList<>();

        while (cursor.hasNext()) {
            NumberedLine rowLine = cursor.next();
            int lineNumber = rowLine.number();
            List<String> cells = splitCsvLine(rowLine.text());
            BigInteger count = parseCount(cells.getFirst(), lineNumber);

            List<Set<Candidate>> ranking = new ArrayList<>();
            boolean seenBlank = false;
            for (int col = 1; col < cells.size(); col++) {
                String cell = cells.get(col).trim();
                if (cell.isEmpty()) {
                    seenBlank = true;
                    continue;
                }
                if (seenBlank) {
                    throw new BallotParseException(
                            "Row " + lineNumber + ": ranking has a gap (a blank cell followed by a preference)");
                }
                Set<Candidate> rank = new LinkedHashSet<>();
                for (String name : cell.split("=")) {
                    String trimmedName = name.trim();
                    if (trimmedName.isEmpty()) {
                        throw new BallotParseException(
                                "Row " + lineNumber + ": empty candidate name in tie group '" + cell + "'");
                    }
                    rank.add(candidatesByName.computeIfAbsent(trimmedName, Candidate::of));
                }
                ranking.add(rank);
            }
            ballots.add(new Ballot(ranking, count));
        }

        return new ParsedBallots(List.copyOf(candidatesByName.values()), ballots);
    }

    private static BigInteger parseCount(String token, int lineNumber) {
        try {
            BigInteger value = new BigInteger(token.trim());
            if (value.signum() <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException _) {
            throw new BallotParseException("Row " + lineNumber + ": invalid ballot count '" + token + "'");
        }
    }

    /**
     * Whether we're outside quotes, inside quotes, or just saw a {@code "} while inside quotes
     * and haven't yet seen the following character that decides what it meant (an escaped
     * {@code ""}, or the actual closing quote).
     */
    private enum QuoteState {
        UNQUOTED, QUOTED, QUOTE_PENDING
    }

    private static List<String> splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        QuoteState state = QuoteState.UNQUOTED;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            state = switch (state) {
                case UNQUOTED -> {
                    if (c == '"') {
                        yield QuoteState.QUOTED;
                    } else if (c == ',') {
                        cells.add(current.toString());
                        current.setLength(0);
                        yield QuoteState.UNQUOTED;
                    } else {
                        current.append(c);
                        yield QuoteState.UNQUOTED;
                    }
                }
                case QUOTED -> {
                    if (c == '"') {
                        yield QuoteState.QUOTE_PENDING;
                    } else {
                        current.append(c);
                        yield QuoteState.QUOTED;
                    }
                }
                case QUOTE_PENDING -> {
                    if (c == '"') {
                        current.append('"');
                        yield QuoteState.QUOTED;
                    } else if (c == ',') {
                        cells.add(current.toString());
                        current.setLength(0);
                        yield QuoteState.UNQUOTED;
                    } else {
                        current.append(c);
                        yield QuoteState.UNQUOTED;
                    }
                }
            };
        }
        cells.add(current.toString());
        return cells;
    }
}
