package info.fkhodkov.rankedchoice.stv.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;

class WeightedTransferableTransferTest {

    private static final TruncateDecimal WHOLE_VOTES = new TruncateDecimal(0);

    @Test
    void scalesTransferablePapersBySurplusOverTransferableVotes() {
        // Newcastle ward 4, 2024: Adamczyk's 10,399 first preferences, 2 of them with no further
        // preference; surplus 4,172. Surplus fraction 4,172 / 10,397, as the official sheet shows.
        HeldParcel transferable = new HeldParcel(1, 0, Fraction.ONE, BigInteger.valueOf(10397), true);
        HeldParcel exhausted = new HeldParcel(2, 0, Fraction.ONE, BigInteger.valueOf(2), false);

        Map<Integer, Fraction> result = WeightedTransferableTransfer.INSTANCE.computeTransferValues(
                Fraction.of(4172), Fraction.of(10399), List.of(transferable, exhausted), WHOLE_VOTES);

        // The papers with no further preference stay with the elected candidate.
        assertThat(result).containsOnly(Map.entry(1, Fraction.of(4172, 10397)));
    }

    @Test
    void weighsEachPaperByTheValueItArrivedAt() {
        HeldParcel whole = new HeldParcel(1, 0, Fraction.ONE, BigInteger.valueOf(30), true);
        HeldParcel half = new HeldParcel(2, 3, Fraction.of(1, 2), BigInteger.valueOf(20), true);

        // 40 votes held, surplus 10: a quarter of each paper's value.
        Map<Integer, Fraction> result = WeightedTransferableTransfer.INSTANCE.computeTransferValues(
                Fraction.of(10), Fraction.of(40), List.of(whole, half), WHOLE_VOTES);

        assertThat(result).containsOnly(Map.entry(1, Fraction.of(1, 4)), Map.entry(2, Fraction.of(1, 8)));
    }

    @Test
    void movesPapersAtTheirValueWhenTheTransferableVotesDontExceedTheSurplus() {
        HeldParcel transferable = new HeldParcel(1, 0, Fraction.ONE, BigInteger.valueOf(5), true);
        HeldParcel exhausted = new HeldParcel(2, 0, Fraction.ONE, BigInteger.valueOf(30), false);

        Map<Integer, Fraction> result = WeightedTransferableTransfer.INSTANCE.computeTransferValues(
                Fraction.of(10), Fraction.of(35), List.of(transferable, exhausted), WHOLE_VOTES);

        assertThat(result).containsOnly(Map.entry(1, Fraction.ONE));
    }

    @Test
    void roundsEachCandidatesGainPerParcel() {
        assertThat(WeightedTransferableTransfer.INSTANCE.roundsGainsPerStep()).isTrue();
        assertThat(WeightedTransferableTransfer.INSTANCE.roundsGainsPerParcel()).isTrue();
    }
}
