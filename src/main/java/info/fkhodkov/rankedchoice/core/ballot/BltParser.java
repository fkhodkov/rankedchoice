package info.fkhodkov.rankedchoice.core.ballot;

import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Blank lines and lines starting with {@code #} are ignored anywhere in the file.
 */
public final class BltParser {

    private BltParser() {
    }

    public static ParsedElection parse(Path path) {
        try {
            return parse(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read BLT file: " + path, e);
        }
    }

    public static ParsedElection parse(String content) {
        List<String> lines = significantLines(content);
        Cursor cursor = new Cursor(lines);

        String header = cursor.next("'<candidates> <seats>' header");
        String[] headerTokens = header.split("\\s+");
        if (headerTokens.length < 2) {
            throw new BallotParseException("Expected '<candidates> <seats>' header, got: '" + header + "'");
        }
        int numCandidates = parseInt(headerTokens[0], "candidate count");
        int numSeats = parseInt(headerTokens[1], "seat count");
        if (numCandidates <= 0) {
            throw new BallotParseException("Candidate count must be positive, got: " + numCandidates);
        }

        Set<Integer> withdrawnIds = new LinkedHashSet<>();
        String line = cursor.next("withdrawn-candidates line or first ballot line");
        while (isWithdrawnLine(line)) {
            for (String token : line.split("\\s+")) {
                withdrawnIds.add(-Integer.parseInt(token));
            }
            line = cursor.next("first ballot line");
        }

        List<RawBallot> rawBallots = new ArrayList<>();
        while (!line.equals("0")) {
            rawBallots.add(parseBallotLine(line));
            line = cursor.next("next ballot line or end-of-ballots marker '0'");
        }

        List<Candidate> candidates = new ArrayList<>(numCandidates);
        for (int i = 1; i <= numCandidates; i++) {
            String name = unquote(cursor.next("quoted name for candidate " + i));
            candidates.add(new Candidate(String.valueOf(i), name));
        }

        String title = cursor.hasNext() ? unquote(cursor.next("election title")) : null;

        Map<Integer, Candidate> byId = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            byId.put(i + 1, candidates.get(i));
        }

        Set<Candidate> withdrawn = new LinkedHashSet<>();
        for (int id : withdrawnIds) {
            withdrawn.add(requireCandidate(byId, id));
        }

        List<Ballot> ballots = new ArrayList<>(rawBallots.size());
        for (RawBallot raw : rawBallots) {
            List<Set<Candidate>> ranking = new ArrayList<>(raw.ranks().size());
            for (List<Integer> rank : raw.ranks()) {
                Set<Candidate> rankCandidates = new LinkedHashSet<>();
                for (int id : rank) {
                    rankCandidates.add(requireCandidate(byId, id));
                }
                ranking.add(rankCandidates);
            }
            ballots.add(new Ballot(ranking, raw.count()));
        }

        return new ParsedElection(title, candidates, withdrawn, numSeats, ballots);
    }

    private record RawBallot(BigInteger count, List<List<Integer>> ranks) {
    }

    private static RawBallot parseBallotLine(String line) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 2 || !tokens[tokens.length - 1].equals("0")) {
            throw new BallotParseException("Ballot line must end with a '0' terminator: '" + line + "'");
        }
        BigInteger count = parseCount(tokens[0]);
        List<List<Integer>> ranks = new ArrayList<>(tokens.length - 2);
        for (int i = 1; i < tokens.length - 1; i++) {
            List<Integer> rank = new ArrayList<>();
            for (String idToken : tokens[i].split("=")) {
                rank.add(parseInt(idToken, "candidate id"));
            }
            ranks.add(rank);
        }
        return new RawBallot(count, ranks);
    }

    private static boolean isWithdrawnLine(String line) {
        String[] tokens = line.split("\\s+");
        for (String token : tokens) {
            try {
                if (Integer.parseInt(token) >= 0) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static Candidate requireCandidate(Map<Integer, Candidate> byId, int id) {
        Candidate candidate = byId.get(id);
        if (candidate == null) {
            throw new BallotParseException("Candidate id out of range: " + id);
        }
        return candidate;
    }

    private static int parseInt(String token, String what) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new BallotParseException("Invalid " + what + ": '" + token + "'");
        }
    }

    private static BigInteger parseCount(String token) {
        try {
            BigInteger value = new BigInteger(token);
            if (value.signum() <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BallotParseException("Invalid ballot count: '" + token + "'");
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        throw new BallotParseException("Expected a quoted string, got: '" + s + "'");
    }

    private static List<String> significantLines(String content) {
        List<String> result = new ArrayList<>();
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            result.add(line);
        }
        return result;
    }

    private static final class Cursor {
        private final List<String> lines;
        private int index = 0;

        Cursor(List<String> lines) {
            this.lines = lines;
        }

        boolean hasNext() {
            return index < lines.size();
        }

        String next(String expected) {
            if (!hasNext()) {
                throw new BallotParseException("Unexpected end of input, expected: " + expected);
            }
            return lines.get(index++);
        }
    }
}
