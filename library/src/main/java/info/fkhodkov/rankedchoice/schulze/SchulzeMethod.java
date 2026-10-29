package info.fkhodkov.rankedchoice.schulze;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Interruption;

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
 * <p>{@code a} beats {@code b} in the final ranking iff {@code p[a][b] > p[b][a]}. Schulze proves
 * this relation transitive, but not that candidates neither of which beats the other rank alike:
 * {@code a} may beat {@code c} while {@code b} beats neither. So the ranking isn't by how many
 * others each candidate beats, which could put a candidate nobody beats below first. First place
 * is every candidate nobody beats, the paper's winners; each later place, every candidate beaten
 * only by those placed above. Verified against Wikipedia's worked 5-candidate example
 * ({@link SchulzeMethodTest}), a real Debian General Resolution's published pairwise matrix
 * ({@link SchulzeDebianGr2004002FixtureTest}), and OpenSTV's Condorcet count
 * ({@code tools/reference/openstv}).
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
            Interruption.check();
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

    /**
     * The ranking by {@code p}. As beating is transitive, a candidate's place is one below the
     * lowest place of those who beat it, and those who beat it are beaten by fewer candidates
     * than it is, so placing candidates in order of how many beat them places every one's
     * beaters first.
     */
    private static List<Set<Candidate>> rank(List<Candidate> candidates, Map<Candidate, Map<Candidate, BigInteger>> p) {
        int n = candidates.size();
        boolean[][] beats = new boolean[n][n];
        int[] beatenBy = new int[n];
        for (int a = 0; a < n; a++) {
            Candidate candidateA = candidates.get(a);
            for (int b = 0; b < n; b++) {
                Candidate candidateB = candidates.get(b);
                if (a != b && p.get(candidateA).get(candidateB).compareTo(p.get(candidateB).get(candidateA)) > 0) {
                    beats[a][b] = true;
                    beatenBy[b]++;
                }
            }
        }
        Integer[] order = new Integer[n];
        for (int c = 0; c < n; c++) {
            order[c] = c;
        }
        Arrays.sort(order, Comparator.comparingInt(c -> beatenBy[c]));
        int[] place = new int[n];
        int places = 0;
        for (int c : order) {
            for (int a = 0; a < n; a++) {
                if (beats[a][c]) {
                    place[c] = Math.max(place[c], place[a] + 1);
                }
            }
            places = Math.max(places, place[c] + 1);
        }
        List<Set<Candidate>> ranking = new ArrayList<>();
        for (int k = 0; k < places; k++) {
            ranking.add(new LinkedHashSet<>());
        }
        for (int c = 0; c < n; c++) {
            ranking.get(place[c]).add(candidates.get(c));
        }
        return ranking;
    }
}
