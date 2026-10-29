package info.fkhodkov.rankedchoice.schulzestv;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link SchulzeStvCounter#completions}, every member's patterns read from each group's levels
 * at once, against {@link SchulzeStvCounter#patterns}, one member's compared with every other:
 * on random reductions with ties at every level, and with counts past {@code long}, each
 * member's patterns have the same voters.
 */
class CompletionsTest {

    @Test
    void everyMembersPatternsAreThoseFoundOneByOne() {
        Random random = new Random(20261029);
        int checked = 0;
        for (int trial = 0; trial < 500; trial++) {
            int size = 2 + random.nextInt(12);
            int groups = random.nextInt(40);
            boolean huge = random.nextInt(5) == 0;
            int[][] levels = new int[groups][];
            BigInteger[] counts = new BigInteger[groups];
            for (int b = 0; b < groups; b++) {
                int[] rank = new int[size];
                for (int j = 0; j < size; j++) {
                    rank[j] = random.nextInt(1 + random.nextInt(size));
                }
                levels[b] = asLevels(rank);
                BigInteger count = BigInteger.valueOf(1 + random.nextInt(30));
                counts[b] = huge ? count.shiftLeft(62) : count;
            }
            SchulzeStvCounter.Reduced reduced = new SchulzeStvCounter.Reduced(levels, counts);
            SchulzeStvCounter.Completion[] completions = SchulzeStvCounter.completions(reduced, size);
            for (int k = 0; k < size; k++) {
                SchulzeStvCounter.Completion completion = completions[k];
                Map<Long, BigInteger> patterns = new HashMap<>();
                for (int i = 0; i < completion.counts().length; i++) {
                    patterns.merge(completion.prefers()[i] | completion.ties()[i] << (size - 1),
                            completion.counts()[i], BigInteger::add);
                }
                assertThat(completion.seats()).isEqualTo(size - 1);
                assertThat(patterns).as("trial %d, member %d", trial, k)
                        .isEqualTo(SchulzeStvCounter.patterns(reduced, k, size));
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(3000);
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
