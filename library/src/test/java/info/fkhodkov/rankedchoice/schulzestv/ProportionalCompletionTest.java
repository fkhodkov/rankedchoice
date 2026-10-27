package info.fkhodkov.rankedchoice.schulzestv;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * The closed form's support against proportional completion (section 8.1.1) listed out as the
 * paper defines it, in exact fractions: a voter level with g on T is replaced by the voters who
 * aren't level with g on all of T, in proportion, each passing on what they say about T, and
 * completed in turn for what they leave level; with no such voters, every order of T and g is
 * equally likely. The closed form doesn't list completions (see {@link
 * SchulzeStvCounter.Completion}), so this checks that its shortcut is the definition.
 */
class ProportionalCompletionTest {

    @Test
    void theClosedFormIsTheListedOutCompletionOnRandomContests() {
        Random random = new Random(20261027);
        int checked = 0;
        for (int trial = 0; trial < 300; trial++) {
            int size = 2 + random.nextInt(6);
            int groups = 1 + random.nextInt(10);
            int levelsUsed = 1 + random.nextInt(size);
            int[][] levels = new int[groups][size];
            BigInteger[] counts = new BigInteger[groups];
            for (int b = 0; b < groups; b++) {
                for (int j = 0; j < size; j++) {
                    levels[b][j] = random.nextInt(levelsUsed);
                }
                if (random.nextInt(3) == 0) {
                    // Ranked first, the rest unranked: level below every ranked member.
                    int ranked = random.nextInt(size);
                    for (int j = 0; j < size; j++) {
                        levels[b][j] = j < ranked ? j : ranked;
                    }
                }
                counts[b] = BigInteger.valueOf(1 + random.nextInt(9));
            }
            SchulzeStvCounter.Reduced reduced = new SchulzeStvCounter.Reduced(levels, counts);
            for (int k = 0; k < size; k++) {
                assertThat(SchulzeStvCounter.closedFormSupport(reduced, k))
                        .as("trial %d, member %d", trial, k)
                        .isEqualTo(listedOut(levels, counts, k));
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(1000);
    }

    /** N[the others; member k]: the minimum over non-empty J of the completed weight preferring some of J, over |J|. */
    private static Fraction listedOut(int[][] levels, BigInteger[] counts, int k) {
        int size = levels[0].length;
        int seats = size - 1;
        long[] prefers = new long[levels.length];
        long[] ties = new long[levels.length];
        for (int b = 0; b < levels.length; b++) {
            for (int j = 0, o = 0; j < size; j++) {
                if (j != k) {
                    if (levels[b][j] < levels[b][k]) {
                        prefers[b] |= 1L << o;
                    } else if (levels[b][j] == levels[b][k]) {
                        ties[b] |= 1L << o;
                    }
                    o++;
                }
            }
        }
        Map<Long, Fraction> completed = new HashMap<>();
        for (int b = 0; b < levels.length; b++) {
            Fraction count = Fraction.of(counts[b]);
            for (Map.Entry<Long, Fraction> above : completion(ties[b], prefers, ties, counts).entrySet()) {
                completed.merge(prefers[b] | above.getKey(), count.multiply(above.getValue()), Fraction::add);
            }
        }
        Fraction best = null;
        for (long j = 1; j < 1L << seats; j++) {
            Fraction supply = Fraction.ZERO;
            for (Map.Entry<Long, Fraction> voter : completed.entrySet()) {
                if ((voter.getKey() & j) != 0) {
                    supply = supply.add(voter.getValue());
                }
            }
            Fraction support = supply.divide(Fraction.of(Long.bitCount(j)));
            if (best == null || support.compareTo(best) < 0) {
                best = support;
            }
        }
        return best;
    }

    /** How a voter level with g on {@code tie} is completed: each set of its members above g, with its share. */
    private static Map<Long, Fraction> completion(long tie, long[] prefers, long[] ties, BigInteger[] counts) {
        Map<Long, Fraction> shares = new HashMap<>();
        if (tie == 0) {
            shares.put(0L, Fraction.ONE);
            return shares;
        }
        BigInteger donors = BigInteger.ZERO;
        for (int d = 0; d < counts.length; d++) {
            if ((ties[d] & tie) != tie) {
                donors = donors.add(counts[d]);
            }
        }
        if (donors.signum() == 0) {
            // Every order of the k tied and g alike: s of them above g in s! (k - s)! of (k + 1)!.
            int k = Long.bitCount(tie);
            for (long a = 0; a < 1L << k; a++) {
                int s = Long.bitCount(a);
                shares.put(Long.expand(a, tie), Fraction.of(factorial(s).multiply(factorial(k - s)), factorial(k + 1)));
            }
            return shares;
        }
        for (int d = 0; d < counts.length; d++) {
            if ((ties[d] & tie) == tie) {
                continue;
            }
            Fraction share = Fraction.of(counts[d], donors);
            for (Map.Entry<Long, Fraction> inner : completion(ties[d] & tie, prefers, ties, counts).entrySet()) {
                shares.merge((prefers[d] & tie) | inner.getKey(), share.multiply(inner.getValue()), Fraction::add);
            }
        }
        return shares;
    }

    private static BigInteger factorial(int n) {
        BigInteger result = BigInteger.ONE;
        for (int i = 2; i <= n; i++) {
            result = result.multiply(BigInteger.valueOf(i));
        }
        return result;
    }
}
