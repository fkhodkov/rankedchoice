package info.fkhodkov.rankedchoice.stv.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;

class WeightedInclusiveTransferTest {

    @Test
    void scalesEveryPaperBySurplusOverTheRecordedVotes() {
        // 30 first preferences and 20 papers received at a half, 5 of them with no further
        // preference: the recorded 40 votes give a surplus fraction of 10 / 40, applied to every
        // paper's own value (WA's continued transfer value), the 5 included.
        HeldParcel first = new HeldParcel(1, 0, Fraction.ONE, BigInteger.valueOf(30), true);
        HeldParcel half = new HeldParcel(2, 3, Fraction.of(1, 2), BigInteger.valueOf(15), true);
        HeldParcel halfExhausted = new HeldParcel(3, 3, Fraction.of(1, 2), BigInteger.valueOf(5), false);

        Map<Integer, Fraction> result = WeightedInclusiveTransfer.INSTANCE.computeTransferValues(
                Fraction.of(10), Fraction.of(40), List.of(first, half, halfExhausted), new TruncateDecimal(0));

        assertThat(result).containsOnly(Map.entry(1, Fraction.of(1, 4)), Map.entry(2, Fraction.of(1, 8)),
                Map.entry(3, Fraction.of(1, 8)));
    }

    @Test
    void roundsEachCandidatesGainPerParcel() {
        assertThat(WeightedInclusiveTransfer.INSTANCE.roundsGainsPerStep()).isTrue();
        assertThat(WeightedInclusiveTransfer.INSTANCE.roundsGainsPerParcel()).isTrue();
    }
}
