package info.fkhodkov.rankedchoice.schulzestv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Random;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * {@link SchulzeStvCounter.Completion#approximateWithinByPlaces}, the weights within every set by
 * a transform over each voter's places, against {@link
 * SchulzeStvCounter.Completion#approximateWithinByVoters}, voter by voter, on random patterns of
 * up to 12 members: some with every voter level on everything, so no one decides, some with
 * nobody level at all. Up to 6 members, every set is also checked against the exact sum.
 */
class WithinByPlacesTest {

    @Test
    void theTransformGivesWhatTheVotersDo() {
        Random random = new Random(20261029);
        int exact = 0;
        for (int trial = 0; trial < 400; trial++) {
            int seats = 1 + random.nextInt(12);
            int full = (1 << seats) - 1;
            int n = 1 + random.nextInt(60);
            int shape = random.nextInt(6); // 0: everyone level on everything; 1: no one level on anything
            long[] prefers = new long[n];
            long[] ties = new long[n];
            BigInteger[] counts = new BigInteger[n];
            for (int d = 0; d < n; d++) {
                int tie = shape == 0 ? full : shape == 1 ? 0 : random.nextInt(full + 1) & random.nextInt(full + 1);
                prefers[d] = random.nextInt(full + 1) & ~tie;
                ties[d] = tie;
                counts[d] = BigInteger.valueOf(1 + random.nextInt(1000));
            }
            SchulzeStvCounter.Completion completion = new SchulzeStvCounter.Completion(prefers, ties, counts, seats);
            double[] byPlaces = completion.approximateWithinByPlaces();
            double[] byVoters = completion.approximateWithinByVoters();
            double total = completion.total().doubleValue();
            assertThat(byPlaces).as("trial %d", trial).hasSize(full + 1);
            for (int set = 0; set <= full; set++) {
                assertThat(byPlaces[set]).as("trial %d, set %d", trial, set)
                        .isCloseTo(byVoters[set], within(1e-12 * total));
                if (seats <= 6) {
                    Fraction sum = completion.exactWithin(set);
                    double exactSum = sum.toBigDecimal(20, RoundingMode.HALF_EVEN).doubleValue();
                    assertThat(byPlaces[set]).as("trial %d, set %d exactly", trial, set)
                            .isCloseTo(exactSum, within(1e-12 * total));
                    exact++;
                }
            }
        }
        assertThat(exact).isGreaterThan(4000);
    }
}
