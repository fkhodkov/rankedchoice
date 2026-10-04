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
        List<String> header = splitCsvLine(headerLine.text());
        if (header.isEmpty() || !header.getFirst().trim().equalsIgnoreCase("count")) {
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
        Iterator<String> cells = splitCsvLine(rowLine.text()).iterator();
        BigInteger count = parseCount(cells.next(), lineNumber);

        List<Set<Candidate>> ranking = new ArrayList<>();
        while (cells.hasNext()) {
            String cell = cells.next().trim();
            if (cell.isEmpty()) {
                cells.forEachRemaining(remaining -> {
                    if (!remaining.isBlank()) {
                        throw new BallotParseException(
                            "Row " + lineNumber + ": ranking has a gap (a blank cell followed by a preference)");
                    }
                });
                break;
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
            ranking.add(rank.size() == 1 ? alone.computeIfAbsent(rank.iterator().next(), Set::of) : rank);
        }
        return new Ballot(ranking, count);
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

    private static List<String> splitCsvLine(String line) {
        State state = State.start();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            state = switch (state.quoteState) {
                case UNQUOTED -> switch (c) {
                    case '"' -> new State(QuoteState.QUOTED, state.current, state.cells);
                    case ',' -> state.addCurrent();
                    default -> new State(QuoteState.UNQUOTED, state.current.append(c), state.cells);
                };
                case QUOTED -> c == '"'
                    ? new State(QuoteState.QUOTE_PENDING, state.current, state.cells)
                    : new State(QuoteState.QUOTED, state.current.append(c), state.cells);
                case QUOTE_PENDING -> switch (c) {
                    case '"' -> new State(QuoteState.QUOTED, state.current.append(c), state.cells);
                    case ',' -> state.addCurrent();
                    default -> new State(QuoteState.UNQUOTED, state.current.append(c), state.cells);
                };
            };
        }
        state.addCurrent();
        return state.cells;
    }

    private record State(QuoteState quoteState, StringBuilder current, List<String> cells) {
        static State start() {
            return new State(QuoteState.UNQUOTED, new StringBuilder(), new ArrayList<>());
        }

        State addCurrent() {
            cells.add(current.toString());
            current.setLength(0);
            return new State(QuoteState.UNQUOTED, current, cells);
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
}
