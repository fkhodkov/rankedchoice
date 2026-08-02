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
 * Parses the de facto ".blt" ballot format used by tools such as OpenSTV:
 *
 * <pre>
 * &lt;num candidates&gt; &lt;num seats&gt;
 * [&lt;withdrawn candidate ids, negative, e.g. "-2 -5"&gt;]
 * &lt;count&gt; &lt;ranked candidate ids, ties as "2=3"&gt; 0
 * ...
 * 0
 * "Candidate 1 name"
 * "Candidate 2 name"
 * ...
 * ["Election title"]
 * </pre>
 *
 * Blank lines and lines starting with {@code #} are ignored anywhere in the file. Error
 * messages reference the 1-based line number in the original input, not the position among
 * the significant (non-blank, non-comment) lines.
 */
public final class BltParser {

    private BltParser() {
    }

    public static ParsedElection parse(Path path) {
        try (Stream<String> lines = Files.lines(path)) {
            return parse(lines);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read BLT file: " + path, e);
        }
    }

    public static ParsedElection parse(Stream<String> lines) {
        Iterator<NumberedLine> cursor = significantLines(lines).iterator();

        NumberedLine header = next(cursor, "'<candidates> <seats>' header");
        String[] headerTokens = header.text().split("\\s+");
        if (headerTokens.length < 2) {
            throw new BallotParseException(
                    "Line " + header.number() + ": expected '<candidates> <seats>' header, got: '"
                            + header.text() + "'");
        }
        int numCandidates = parseInt(headerTokens[0], "candidate count", header.number());
        int numSeats = parseInt(headerTokens[1], "seat count", header.number());
        if (numCandidates <= 0) {
            throw new BallotParseException(
                    "Line " + header.number() + ": candidate count must be positive, got: " + numCandidates);
        }

        // id -> the line it was declared withdrawn on, for error reporting if it's out of range.
        Map<Integer, Integer> withdrawnIdLines = new LinkedHashMap<>();
        NumberedLine line = next(cursor, "withdrawn-candidates line or first ballot line");
        while (isWithdrawnLine(line.text())) {
            for (String token : line.text().split("\\s+")) {
                withdrawnIdLines.putIfAbsent(-Integer.parseInt(token), line.number());
            }
            line = next(cursor, "first ballot line");
        }

        List<RawBallot> rawBallots = new ArrayList<>();
        while (!line.text().equals("0")) {
            rawBallots.add(parseBallotLine(line));
            line = next(cursor, "next ballot line or end-of-ballots marker '0'");
        }

        List<Candidate> candidates = new ArrayList<>(numCandidates);
        for (int i = 1; i <= numCandidates; i++) {
            NumberedLine nameLine = next(cursor, "quoted name for candidate " + i);
            candidates.add(new Candidate(String.valueOf(i), unquote(nameLine)));
        }

        String title = cursor.hasNext() ? unquote(next(cursor, "election title")) : null;

        Map<Integer, Candidate> byId = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            byId.put(i + 1, candidates.get(i));
        }

        Set<Candidate> withdrawn = new LinkedHashSet<>();
        for (Map.Entry<Integer, Integer> entry : withdrawnIdLines.entrySet()) {
            withdrawn.add(requireCandidate(byId, entry.getKey(), entry.getValue()));
        }

        List<Ballot> ballots = new ArrayList<>(rawBallots.size());
        for (RawBallot raw : rawBallots) {
            List<Set<Candidate>> ranking = new ArrayList<>(raw.ranks().size());
            for (List<Integer> rank : raw.ranks()) {
                Set<Candidate> rankCandidates = new LinkedHashSet<>();
                for (int id : rank) {
                    rankCandidates.add(requireCandidate(byId, id, raw.lineNumber()));
                }
                ranking.add(rankCandidates);
            }
            ballots.add(new Ballot(ranking, raw.count()));
        }

        return new ParsedElection(title, candidates, withdrawn, numSeats, ballots);
    }

    private record RawBallot(int lineNumber, BigInteger count, List<List<Integer>> ranks) {
    }

    private static RawBallot parseBallotLine(NumberedLine line) {
        String[] tokens = line.text().split("\\s+");
        if (tokens.length < 2 || !tokens[tokens.length - 1].equals("0")) {
            throw new BallotParseException(
                    "Line " + line.number() + ": ballot line must end with a '0' terminator: '" + line.text() + "'");
        }
        BigInteger count = parseCount(tokens[0], line.number());
        List<List<Integer>> ranks = new ArrayList<>(tokens.length - 2);
        for (int i = 1; i < tokens.length - 1; i++) {
            List<Integer> rank = new ArrayList<>();
            for (String idToken : tokens[i].split("=")) {
                rank.add(parseInt(idToken, "candidate id", line.number()));
            }
            ranks.add(rank);
        }
        return new RawBallot(line.number(), count, ranks);
    }

    private static boolean isWithdrawnLine(String line) {
        String[] tokens = line.split("\\s+");
        for (String token : tokens) {
            try {
                if (Integer.parseInt(token) >= 0) {
                    return false;
                }
            } catch (NumberFormatException _) {
                return false;
            }
        }
        return true;
    }

    private static Candidate requireCandidate(Map<Integer, Candidate> byId, int id, int lineNumber) {
        Candidate candidate = byId.get(id);
        if (candidate == null) {
            throw new BallotParseException("Line " + lineNumber + ": candidate id out of range: " + id);
        }
        return candidate;
    }

    private static int parseInt(String token, String what, int lineNumber) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException _) {
            throw new BallotParseException("Line " + lineNumber + ": invalid " + what + ": '" + token + "'");
        }
    }

    private static BigInteger parseCount(String token, int lineNumber) {
        try {
            BigInteger value = new BigInteger(token);
            if (value.signum() <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException _) {
            throw new BallotParseException("Line " + lineNumber + ": invalid ballot count: '" + token + "'");
        }
    }

    private static String unquote(NumberedLine line) {
        String s = line.text();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        throw new BallotParseException("Line " + line.number() + ": expected a quoted string, got: '" + s + "'");
    }

    private static Stream<NumberedLine> significantLines(Stream<String> lines) {
        return NumberedLine.number(lines)
                .map(numbered -> new NumberedLine(numbered.number(), numbered.text().trim()))
                .filter(numbered -> !numbered.text().isEmpty() && !numbered.text().startsWith("#"));
    }

    private static NumberedLine next(Iterator<NumberedLine> lines, String expected) {
        if (!lines.hasNext()) {
            throw new BallotParseException("Unexpected end of input, expected: " + expected);
        }
        return lines.next();
    }
}
