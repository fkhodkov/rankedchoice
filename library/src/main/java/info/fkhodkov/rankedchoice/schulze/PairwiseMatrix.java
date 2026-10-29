package info.fkhodkov.rankedchoice.schulze;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Interruption;

/**
 * The pairwise preference matrix {@code d} that every Condorcet-family method (Schulze
 * included) starts from: {@code d[a][b]} is the number of ballots that rank {@code a} strictly
 * ahead of {@code b}, weighted by each ballot's {@link Ballot#count()}.
 *
 * <p>A candidate this project's {@link Ballot} model leaves off a ranking entirely is treated
 * as tied for last on that ballot, behind every candidate the voter did rank — the standard
 * convention (an unranked candidate is simply not preferred to anything the voter bothered to
 * rank) — so an unranked candidate contributes no comparisons against another unranked
 * candidate on the same ballot, exactly as a tie group within an explicit ranking wouldn't.
 * Unlike {@link info.fkhodkov.rankedchoice.stv.StvCounter} and {@link
 * info.fkhodkov.rankedchoice.stv.meek.MeekCounter}, this project's Condorcet methods accept
 * tied rankings directly — a pairwise comparison has nothing that needs a transfer ratio, so
 * there's no reason to reject them.
 */
public final class PairwiseMatrix {

    private PairwiseMatrix() {
    }

    public static Map<Candidate, Map<Candidate, BigInteger>> compute(List<Candidate> candidates, List<Ballot> ballots) {
        long[][] d = countInLongs(candidates, ballots);
        if (d == null) {
            return computeInBigIntegers(candidates, ballots);
        }
        Map<Candidate, Map<Candidate, BigInteger>> result = new LinkedHashMap<>();
        for (int a = 0; a < candidates.size(); a++) {
            Map<Candidate, BigInteger> row = new LinkedHashMap<>();
            for (int b = 0; b < candidates.size(); b++) {
                if (a != b) {
                    row.put(candidates.get(b), BigInteger.valueOf(d[a][b]));
                }
            }
            result.put(candidates.get(a), row);
        }
        return result;
    }

    /**
     * The matrix in {@code long}s, touching only the candidates each ballot ranks: a ranked
     * candidate beats every candidate except those ranked at the same position or ahead of it
     * (itself included), so {@code d[a][b]} is the number of ballots ranking {@code a} minus
     * those that also rank {@code b} at or ahead of {@code a}. That's about k^2 steps for a ballot
     * ranking k candidates, rather than one per pair of candidates standing -- which matters
     * with hundreds of candidates and ballots ranking only a handful. Returns {@code null} if a
     * count doesn't fit in a {@code long}, for {@link #computeInBigIntegers} to handle instead.
     */
    private static long[][] countInLongs(List<Candidate> candidates, List<Ballot> ballots) {
        int n = candidates.size();
        Map<Candidate, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < n; i++) {
            indexOf.put(candidates.get(i), i);
        }
        long[] ranked = new long[n];
        long[][] notAhead = new long[n][n];
        int[] members = new int[n];
        int[] positions = new int[n];
        try {
            for (Ballot ballot : ballots) {
                Interruption.check();
                if (ballot.isBlank()) {
                    continue;
                }
                if (ballot.count().bitLength() >= Long.SIZE - 1) {
                    return null;
                }
                long count = ballot.count().longValue();
                int k = 0;
                int position = 0;
                for (Set<Candidate> rank : ballot.ranking()) {
                    for (Candidate candidate : rank) {
                        Integer index = indexOf.get(candidate);
                        if (index != null) {
                            members[k] = index;
                            positions[k++] = position;
                        }
                    }
                    position++;
                }
                for (int i = 0; i < k; i++) {
                    int a = members[i];
                    ranked[a] = Math.addExact(ranked[a], count);
                    for (int j = 0; j < k; j++) {
                        if (j != i && positions[j] <= positions[i]) {
                            notAhead[a][members[j]] = Math.addExact(notAhead[a][members[j]], count);
                        }
                    }
                }
            }
        } catch (ArithmeticException _) {
            return null;
        }
        long[][] d = new long[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                if (a != b) {
                    d[a][b] = ranked[a] - notAhead[a][b];
                }
            }
        }
        return d;
    }

    /** The same matrix for counts too large for a {@code long}, comparing every pair directly. */
    private static Map<Candidate, Map<Candidate, BigInteger>> computeInBigIntegers(
            List<Candidate> candidates, List<Ballot> ballots) {
        Map<Candidate, Map<Candidate, BigInteger>> d = new LinkedHashMap<>();
        for (Candidate a : candidates) {
            Map<Candidate, BigInteger> row = new LinkedHashMap<>();
            for (Candidate b : candidates) {
                if (!a.equals(b)) {
                    row.put(b, BigInteger.ZERO);
                }
            }
            d.put(a, row);
        }

        for (Ballot ballot : ballots) {
            Interruption.check();
            if (ballot.isBlank()) {
                continue;
            }
            Map<Candidate, Integer> position = new HashMap<>();
            int pos = 0;
            for (Set<Candidate> rank : ballot.ranking()) {
                for (Candidate candidate : rank) {
                    position.put(candidate, pos);
                }
                pos++;
            }
            int unrankedPosition = pos;

            for (Candidate a : candidates) {
                int posA = position.getOrDefault(a, unrankedPosition);
                for (Candidate b : candidates) {
                    if (a.equals(b)) {
                        continue;
                    }
                    int posB = position.getOrDefault(b, unrankedPosition);
                    if (posA < posB) {
                        d.get(a).merge(b, ballot.count(), BigInteger::add);
                    }
                }
            }
        }
        return d;
    }
}
