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
        Map<Candidate, Map<Candidate, BigInteger>> p = strongestPaths(candidates, d);
        List<Set<Candidate>> ranking = rank(candidates, p);
        return new SchulzeResult(d, p, ranking);
    }

    private static Map<Candidate, Map<Candidate, BigInteger>> strongestPaths(
            List<Candidate> candidates, Map<Candidate, Map<Candidate, BigInteger>> d) {
        Map<Candidate, Map<Candidate, BigInteger>> p = new LinkedHashMap<>();
        for (Candidate i : candidates) {
            Map<Candidate, BigInteger> row = new LinkedHashMap<>();
            for (Candidate j : candidates) {
                if (i.equals(j)) {
                    continue;
                }
                BigInteger dij = d.get(i).get(j);
                BigInteger dji = d.get(j).get(i);
                row.put(j, dij.compareTo(dji) > 0 ? dij : BigInteger.ZERO);
            }
            p.put(i, row);
        }

        for (Candidate k : candidates) {
            for (Candidate i : candidates) {
                if (i.equals(k)) {
                    continue;
                }
                for (Candidate j : candidates) {
                    if (j.equals(i) || j.equals(k)) {
                        continue;
                    }
                    BigInteger viaK = p.get(i).get(k).min(p.get(k).get(j));
                    if (viaK.compareTo(p.get(i).get(j)) > 0) {
                        p.get(i).put(j, viaK);
                    }
                }
            }
        }
        return p;
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
