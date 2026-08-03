package info.fkhodkov.rankedchoice.stv.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;

class TransferMethodTest {

    @Test
    void bothMethodsAgreeWithASingleParcel() {
        HeldParcel parcel = new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(23), true);
        Fraction surplus = Fraction.of(10);
        TruncateDecimal rounding = new TruncateDecimal(2);

        assertThat(FractionalTransfer.INSTANCE.computeTransferValues(surplus, List.of(parcel), rounding))
                .isEqualTo(WigmTransfer.INSTANCE.computeTransferValues(surplus, List.of(parcel), rounding));
    }

    @Test
    void bothMethodsAgreeUnderExactRoundingRegardlessOfParcelValues() {
        // Without rounding getting in the way, round(x*V)==round(x)*V trivially, for any V.
        List<HeldParcel> parcels = List.of(
                new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(10), true),
                new HeldParcel(2, 1, Fraction.of(43, 100), BigInteger.valueOf(10), true));
        Fraction surplus = Fraction.of(13, 10);

        assertThat(FractionalTransfer.INSTANCE.computeTransferValues(surplus, parcels, Exact.INSTANCE))
                .isEqualTo(WigmTransfer.INSTANCE.computeTransferValues(surplus, parcels, Exact.INSTANCE));
    }

    @Test
    void methodsDivergeForAMixedValueParcelUnderTruncation() {
        // candidateVotes = 10*1 + 10*0.43 = 14.3 = 143/10; surplus = 13/10.
        // surplus/candidateVotes = 13/143 = 1/11 = 0.0909... -> truncates to 0.09 either way,
        // so the value-1 parcel agrees between methods; the 0.43 parcel does not.
        HeldParcel unitParcel = new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(10), true);
        HeldParcel fractionalValueParcel = new HeldParcel(2, 1, Fraction.of(43, 100), BigInteger.valueOf(10), true);
        List<HeldParcel> parcels = List.of(unitParcel, fractionalValueParcel);
        Fraction surplus = Fraction.of(13, 10);
        TruncateDecimal rounding = new TruncateDecimal(2);

        var fractional = FractionalTransfer.INSTANCE.computeTransferValues(surplus, parcels, rounding);
        var wigm = WigmTransfer.INSTANCE.computeTransferValues(surplus, parcels, rounding);

        assertThat(fractional)
                .containsEntry(1, wigm.get(1)) // both 0.09, since value == 1
                // FractionalTransfer: 43/100 * truncate(13/143, 2) = 43/100 * 9/100.
                .containsEntry(2, Fraction.of(43, 100).multiply(Fraction.of(9, 100)))
                .doesNotContainEntry(2, wigm.get(2));
        // WigmTransfer: truncate(13/10 * 43/100 / (143/10), 2) = truncate(43/1100, 2) = 0.03.
        assertThat(wigm).containsEntry(2, Fraction.of(3, 100));
    }

    @Test
    void zeroCandidateVotesReturnsEmptyMapForBothMethods() {
        List<HeldParcel> parcels = List.of(new HeldParcel(1, 1, Fraction.ZERO, BigInteger.ZERO, true));
        assertThat(FractionalTransfer.INSTANCE.computeTransferValues(Fraction.ZERO, parcels, Exact.INSTANCE)).isEmpty();
        assertThat(WigmTransfer.INSTANCE.computeTransferValues(Fraction.ZERO, parcels, Exact.INSTANCE)).isEmpty();
    }
}
