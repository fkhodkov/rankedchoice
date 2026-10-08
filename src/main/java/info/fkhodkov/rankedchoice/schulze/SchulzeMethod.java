package info.fkhodkov.rankedchoice.schulze;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/**
 * Runs a Condorcet count by the Schulze method (Markus Schulze, "The Schulze Method of
 * Voting", most recently arXiv:1804.02973): computes the pairwise preference matrix {@code d}
 * (see {@link PairwiseMatrix}), then the strongest-path ("beatpath") matrix {@code p} — for
 * every pair, the strength of the strongest chain of pairwise wins connecting them, where a
 * chain's strength is its weakest link — via the standard Floyd-Warshall-style closure. This
 * project uses the "winning votes" variant (the direct pairwise vote count is the initial edge
 * weight whenever {@code a} beats {@code b}, not the margin between them) — the variant
 * Schulze's own paper and the Debian project's devotee implementation treat as the default.
 *
 * <p>{@code a} beats {@code b} in the final ranking iff {@code p[a][b] > p[b][a]}; this
 * relation is a proven total preorder (transitive, with no cycles), so ranking candidates by
 * how many others they beat this way — and grouping equal counts together as ties — reproduces
 * the same order as a full pairwise comparison of {@code p}, without needing a separate sort
 * comparator. Verified against Wikipedia's worked 5-candidate example
 * ({@link SchulzeMethodTest}) and against a real Debian General Resolution's published pairwise
 * matrix ({@link SchulzeDebianGr2004002FixtureTest}).
 */
public final class SchulzeMethod {

    private SchulzeMethod() {
    }

    public static SchulzeResult count(List<Candidate> candidates, List<Ballot> ballots) {
        return fromMatrix(candidates, PairwiseMatrix.compute(candidates, ballots));
    }

    /** Runs the strongest-path computation and ranking directly from an already-known {@code d} matrix. */
    public static SchulzeResult fromMatrix(List<Candidate> candidates, Map<Candidate, Map<Candidate, BigInteger>> d) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("there must be at least one candidate");
        }
        Map<Candidate, Map<Candidate, BigInteger>> p = strongestPaths(candidates, d);
        List<Set<Candidate>> ranking = rank(candidates, p);
        return new SchulzeResult(d, p, ranking);
    }

    /**
     * The strongest-path strengths {@code p}, by Floyd-Warshall over arrays indexed like {@code
     * candidates} (hundreds of candidates make this cubic loop the costly part), converted back
     * to maps once at the end.
     */
    private static Map<Candidate, Map<Candidate, BigInteger>> strongestPaths(
            List<Candidate> candidates, Map<Candidate, Map<Candidate, BigInteger>> d) {
        int n = candidates.size();
        BigInteger[][] p = new BigInteger[n][n];
        for (int i = 0; i < n; i++) {
            Map<Candidate, BigInteger> fromI = d.get(candidates.get(i));
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    BigInteger dij = fromI.get(candidates.get(j));
                    BigInteger dji = d.get(candidates.get(j)).get(candidates.get(i));
                    p[i][j] = dij.compareTo(dji) > 0 ? dij : BigInteger.ZERO;
                }
            }
        }

        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                if (i == k) {
                    continue;
                }
                BigInteger iToK = p[i][k];
                if (iToK.signum() == 0) {
                    continue; // no path through k can be stronger than zero
                }
                for (int j = 0; j < n; j++) {
                    if (j == i || j == k) {
                        continue;
                    }
                    BigInteger viaK = iToK.min(p[k][j]);
                    if (viaK.compareTo(p[i][j]) > 0) {
                        p[i][j] = viaK;
                    }
                }
            }
        }

        Map<Candidate, Map<Candidate, BigInteger>> result = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Map<Candidate, BigInteger> row = new LinkedHashMap<>();
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    row.put(candidates.get(j), p[i][j]);
                }
            }
            result.put(candidates.get(i), row);
        }
        return result;
    }

    private static List<Set<Candidate>> rank(List<Candidate> candidates, Map<Candidate, Map<Candidate, BigInteger>> p) {
        Map<Candidate, Integer> wins = new LinkedHashMap<>();
        for (Candidate a : candidates) {
            int count = 0;
            for (Candidate b : candidates) {
                if (!a.equals(b) && p.get(a).get(b).compareTo(p.get(b).get(a)) > 0) {
                    count++;
                }
            }
            wins.put(a, count);
        }

        Map<Integer, Set<Candidate>> groups = new TreeMap<>(java.util.Comparator.reverseOrder());
        for (Candidate candidate : candidates) {
            groups.computeIfAbsent(wins.get(candidate), _ -> new LinkedHashSet<>()).add(candidate);
        }
        return new ArrayList<>(groups.values());
    }
}
