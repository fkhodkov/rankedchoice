package info.fkhodkov.rankedchoice.stv.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;

class LastParcelTransferTest {

    @Test
    void onlyTheMostRecentlyArrivedParcelIsTouched() {
        // Parcel 1 (an earlier transfer) and parcel 2 (the most recent) both held by the
        // same now-elected candidate; only parcel 2 should appear in the result.
        HeldParcel earlier = new HeldParcel(1, /* arrivalParcelId */ 1, Fraction.ONE, BigInteger.valueOf(10));
        HeldParcel last = new HeldParcel(2, /* arrivalParcelId */ 5, Fraction.ONE, BigInteger.valueOf(20));
        Fraction surplus = Fraction.of(5);

        Map<Integer, Fraction> result = LastParcelTransfer.INSTANCE.computeTransferValues(
                surplus, List.of(earlier, last), Exact.INSTANCE);

        // ratio = surplus / lastParcelVotes = 5/20 = 1/4, applied to the last parcel's own value (1).
        assertThat(result).containsOnlyKeys(2).containsEntry(2, Fraction.of(1, 4));
    }

    @Test
    void ratioUsesTheLastParcelsOwnTotalNotTheWholeCandidates() {
        HeldParcel earlier = new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(90));
        HeldParcel last = new HeldParcel(2, 2, Fraction.ONE, BigInteger.valueOf(10));
        Fraction surplus = Fraction.of(5);

        // If the ratio wrongly used the whole candidate (100), it would be 5/100 = 0.05;
        // using just the last parcel's own 10 votes, it's 5/10 = 0.5.
        Map<Integer, Fraction> result = LastParcelTransfer.INSTANCE.computeTransferValues(
                surplus, List.of(earlier, last), Exact.INSTANCE);

        assertThat(result).containsOnlyKeys(2).containsEntry(2, Fraction.of(1, 2));
    }

    @Test
    void appliesRoundingToTheLastParcelRatio() {
        HeldParcel last = new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(23));
        Fraction surplus = Fraction.of(10);

        Map<Integer, Fraction> result = LastParcelTransfer.INSTANCE.computeTransferValues(
                surplus, List.of(last), new TruncateDecimal(2));

        // 10/23 = 0.434782... -> truncates to 0.43.
        assertThat(result).containsEntry(1, Fraction.of(43, 100));
    }

    @Test
    void emptyParcelsGiveAnEmptyResult() {
        assertThat(LastParcelTransfer.INSTANCE.computeTransferValues(Fraction.of(5), List.of(), Exact.INSTANCE))
                .isEmpty();
    }

    @Test
    void zeroVoteLastParcelGivesAnEmptyResult() {
        HeldParcel zeroParcel = new HeldParcel(1, 1, Fraction.ZERO, BigInteger.ZERO);
        assertThat(LastParcelTransfer.INSTANCE.computeTransferValues(Fraction.of(5), List.of(zeroParcel), Exact.INSTANCE))
                .isEmpty();
    }
}
