package info.fkhodkov.rankedchoice.schulzestv;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link MinimumNormSupport} against the closed form, which Schulze's published figures check:
 * on random contests of 2 to 12 candidates, with ties at every level (unranked candidates
 * included, and groups of candidates nobody tells apart), every support is the same fraction.
 */
class MinimumNormSupportTest {

    @Test
    void everySupportIsTheClosedFormsOnRandomContests() {
        Random random = new Random(20261027);
        int checked = 0;
        for (int trial = 0; trial < 600; trial++) {
            int size = 2 + random.nextInt(11);
            int groups = 1 + random.nextInt(30);
            int levelsUsed = 1 + random.nextInt(size);
            boolean undecided = random.nextInt(4) == 0;
            boolean truncated = random.nextBoolean();
            int[][] levels = new int[groups][size];
            BigInteger[] counts = new BigInteger[groups];
            for (int b = 0; b < groups; b++) {
                int[] level = new int[size];
                int ranked = truncated ? random.nextInt(size + 1) : size;
                for (int j = 0; j < size; j++) {
                    level[j] = random.nextInt(levelsUsed);
                }
                if (truncated) {
                    // Like a short ballot: a few members in order, the rest level below them.
                    for (int j = 0; j < size; j++) {
                        level[j] = random.nextInt(size) < ranked ? random.nextInt(size) : size;
                    }
                }
                if (undecided && size > 2) {
                    level[1] = level[0]; // members 0 and 1 level on every ballot
                }
                levels[b] = asLevels(level);
                counts[b] = BigInteger.valueOf(1 + random.nextInt(20));
            }
            SchulzeStvCounter.Reduced reduced = new SchulzeStvCounter.Reduced(levels, counts);
            for (int k = 0; k < size; k++) {
                assertThat(MinimumNormSupport.of(levels, counts, k))
                        .as("trial %d, member %d", trial, k)
                        .isEqualTo(SchulzeStvCounter.closedFormSupport(reduced, k));
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(3500);
    }

    /** Ranks as a reduced contest gives them: each member's level is how many rank ahead of it. */
    private static int[] asLevels(int[] rank) {
        int[] level = new int[rank.length];
        for (int j = 0; j < rank.length; j++) {
            for (int i = 0; i < rank.length; i++) {
                if (rank[i] < rank[j]) {
                    level[j]++;
                }
            }
        }
        return level;
    }
}
