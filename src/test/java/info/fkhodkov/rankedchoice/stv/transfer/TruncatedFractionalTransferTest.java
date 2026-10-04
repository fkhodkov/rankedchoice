package info.fkhodkov.rankedchoice.stv.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;

class TruncatedFractionalTransferTest {

    private static final TruncateDecimal FOUR_PLACES = new TruncateDecimal(4);

    @Test
    void truncatesTheFractionThenEachBallotsValue() {
        // Portland's City Code 2.08.030 C.2.b(1): 1,451 over 12,169 votes is 0.119237..., truncated
        // to 0.1192 (as District 1's Candace Avalos's surplus in 2024); a ballot worth 0.3333
        // then moves at 0.3333 x 0.1192 = 0.03972936, truncated to 0.0397.
        HeldParcel whole = new HeldParcel(1, 0, Fraction.ONE, BigInteger.valueOf(12166), true);
        HeldParcel third = new HeldParcel(2, 4, Fraction.of(3333, 10000), BigInteger.valueOf(9), true);

        Map<Integer, Fraction> result = TruncatedFractionalTransfer.INSTANCE.computeTransferValues(
                Fraction.of(1451), List.of(whole, third), FOUR_PLACES);

        // The candidate's votes: 12,166 + 9 x 0.3333 = 12,168.9997.
        assertThat(result).containsOnly(Map.entry(1, Fraction.of(1192, 10000)), Map.entry(2, Fraction.of(397, 10000)));
    }
}
