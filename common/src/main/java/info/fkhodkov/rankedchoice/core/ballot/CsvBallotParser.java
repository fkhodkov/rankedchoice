package info.fkhodkov.rankedchoice.core.ballot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
        try (Stream<String> lines = Files.lines(path)) {
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
        List<List<String>> header = splitCsvLine(headerLine);
        if (!String.join("=", header.getFirst()).trim().equalsIgnoreCase("count")) {
            throw new BallotParseException(
                "Line " + headerLine.number() + ": CSV header must start with a 'count' column, got: '"
                + headerLine.text() + "'");
        }

        Map<String, Candidate> candidatesByName = new LinkedHashMap<>();
        // One immutable set per candidate, shared by every rank naming that candidate alone.
        Map<Candidate, Set<Candidate>> alone = new HashMap<>();
        List<Ballot> ballots = new ArrayList<>();

        while (cursor.hasNext()) {
            NumberedLine rowLine = cursor.next();
            Ballot ballot = parseLine(rowLine, candidatesByName, alone);
            ballots.add(ballot);
        }

        return new ParsedBallots(List.copyOf(candidatesByName.values()), ballots);
    }

    private static Ballot parseLine(NumberedLine rowLine, Map<String, Candidate> candidatesByName,
            Map<Candidate, Set<Candidate>> alone) {
        int lineNumber = rowLine.number();
        Iterator<List<String>> cells = splitCsvLine(rowLine).iterator();
        BigInteger count = parseCount(String.join("=", cells.next()), lineNumber);

        List<Set<Candidate>> ranking = new ArrayList<>();
        while (cells.hasNext()) {
            List<String> parts = cells.next();
            if (isBlank(parts)) {
                cells.forEachRemaining(remaining -> {
                    if (!isBlank(remaining)) {
                        throw new BallotParseException(
                            "Row " + lineNumber + ": ranking has a gap (a blank cell followed by a preference)");
                    }
                });
                break;
            }
            Set<Candidate> rank = new LinkedHashSet<>();
            for (String name : parts) {
                String trimmedName = name.trim();
                if (trimmedName.isEmpty()) {
                    throw new BallotParseException("Row " + lineNumber + ": empty candidate name in tie group '"
                        + String.join("=", parts) + "'");
                }
                if (!rank.add(candidatesByName.computeIfAbsent(trimmedName, Candidate::of))) {
                    throw new BallotParseException("Row " + lineNumber + ": candidate '" + trimmedName
                        + "' named twice in tie group '" + String.join("=", parts) + "'");
                }
            }
            ranking.add(rank.size() == 1 ? alone.computeIfAbsent(rank.iterator().next(), Set::of) : rank);
        }
        return new Ballot(ranking, count);
    }

    /** A cell with nothing in it but spaces: the end of a ranking. */
    private static boolean isBlank(List<String> parts) {
        return parts.size() == 1 && parts.getFirst().isBlank();
    }

    private static BigInteger parseCount(String token, int lineNumber) {
        try {
            String trimmed = token.trim();
            // valueOf shares the cached instances of small counts (1 above all) across ballots.
            BigInteger value = trimmed.length() < 18
                    ? BigInteger.valueOf(Long.parseLong(trimmed)) : new BigInteger(trimmed);
            if (value.signum() <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException _) {
            throw new BallotParseException("Row " + lineNumber + ": invalid ballot count '" + token + "'");
        }
    }

    /**
     * A line's cells, each as its parts between unquoted {@code =} signs (one part where there's
     * no tie). A quoted {@code ,} or {@code =} is part of a name.
     */
    private static List<List<String>> splitCsvLine(NumberedLine line) {
        List<List<String>> cells = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        QuoteState state = QuoteState.UNQUOTED;
        String text = line.text();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (state == QuoteState.QUOTED) {
                if (c == '"') {
                    state = QuoteState.QUOTE_PENDING;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '"') {
                if (state == QuoteState.QUOTE_PENDING) {
                    current.append('"'); // an escaped quote, "" inside quotes
                }
                state = QuoteState.QUOTED;
                continue;
            }
            state = QuoteState.UNQUOTED;
            if (c == ',' || c == '=') {
                parts.add(current.toString());
                current.setLength(0);
                if (c == ',') {
                    cells.add(parts);
                    parts = new ArrayList<>();
                }
            } else {
                current.append(c);
            }
        }
        if (state == QuoteState.QUOTED) {
            throw new BallotParseException("Line " + line.number() + ": a quoted cell is never closed");
        }
        parts.add(current.toString());
        cells.add(parts);
        return cells;
    }

    /**
     * Whether we're outside quotes, inside quotes, or just saw a {@code "} while inside quotes
     * and haven't yet seen the following character that decides what it meant (an escaped
     * {@code ""}, or the actual closing quote).
     */
    private enum QuoteState {
        UNQUOTED, QUOTED, QUOTE_PENDING
    }
}
