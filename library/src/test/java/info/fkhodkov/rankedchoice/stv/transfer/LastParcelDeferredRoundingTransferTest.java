package info.fkhodkov.rankedchoice.stv.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;

class LastParcelDeferredRoundingTransferTest {

    @Test
    void ratioIsNeverRoundedUnlikeLastParcelTransfer() {
        HeldParcel last = new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(23), true);
        Fraction surplus = Fraction.of(10);

        Map<Integer, Fraction> result = LastParcelDeferredRoundingTransfer.INSTANCE.computeTransferValues(
                surplus, List.of(last), new TruncateDecimal(2));

        // 10/23 stays exact here -- LastParcelTransfer would truncate this same input to 0.43.
        assertThat(result).containsEntry(1, Fraction.of(10, 23));
    }

    @Test
    void onlyTheMostRecentlyArrivedParcelIsTouched() {
        HeldParcel earlier = new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(10), true);
        HeldParcel last = new HeldParcel(2, 5, Fraction.ONE, BigInteger.valueOf(20), true);
        Fraction surplus = Fraction.of(5);

        Map<Integer, Fraction> result = LastParcelDeferredRoundingTransfer.INSTANCE.computeTransferValues(
                surplus, List.of(earlier, last), Exact.INSTANCE);

        assertThat(result).containsOnlyKeys(2).containsEntry(2, Fraction.of(1, 4));
    }

    @Test
    void nonTransferablePapersAreExcludedFromTheRatioAndStayWithTheElectedCandidate() {
        HeldParcel transferable = new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(11099), true);
        HeldParcel nonTransferable = new HeldParcel(2, 1, Fraction.ONE, BigInteger.valueOf(49), false);
        Fraction surplus = Fraction.of(2714);

        Map<Integer, Fraction> result = LastParcelDeferredRoundingTransfer.INSTANCE.computeTransferValues(
                surplus, List.of(transferable, nonTransferable), new TruncateDecimal(6));

        // Real 2020 ACT Kurrajong figures (Andrew Barr's surplus): 2714/11099 kept exact, not
        // truncated to 6dp -- rounding only happens once a destination candidate's own gain
        // (this ratio x their ballot count) is computed, which is StvCounter's job, not this
        // transfer method's. The rounding rule passed in here is unused entirely.
        // The 49 papers with no further preference stay with Barr as part of his quota: the
        // whole 2714 surplus moves on through the other 11099, so they can't also be exhausted.
        assertThat(result).containsOnly(Map.entry(1, Fraction.of(2714, 11099)));
    }

    @Test
    void papersMoveAtTheTransferValueCappedAtTheirOwn() {
        // ACT: votes = BP x TV, a value per paper. 20 papers at 1/4: a surplus of 2 gives each
        // 1/10; a surplus of 6 would give 3/10, more than they're worth, so they keep 1/4.
        HeldParcel fractional = new HeldParcel(1, 3, Fraction.of(1, 4), BigInteger.valueOf(20), true);

        assertThat(LastParcelDeferredRoundingTransfer.INSTANCE.computeTransferValues(
                Fraction.of(2), List.of(fractional), Exact.INSTANCE)).containsOnly(Map.entry(1, Fraction.of(1, 10)));
        assertThat(LastParcelDeferredRoundingTransfer.INSTANCE.computeTransferValues(
                Fraction.of(6), List.of(fractional), Exact.INSTANCE)).containsOnly(Map.entry(1, Fraction.of(1, 4)));
    }

    @Test
    void emptyParcelsGiveAnEmptyResult() {
        assertThat(LastParcelDeferredRoundingTransfer.INSTANCE
                        .computeTransferValues(Fraction.of(5), List.of(), Exact.INSTANCE))
                .isEmpty();
    }

    @Test
    void zeroVoteLastParcelGivesAnEmptyResult() {
        HeldParcel zeroParcel = new HeldParcel(1, 1, Fraction.ZERO, BigInteger.ZERO, true);
        assertThat(LastParcelDeferredRoundingTransfer.INSTANCE
                        .computeTransferValues(Fraction.of(5), List.of(zeroParcel), Exact.INSTANCE))
                .isEmpty();
    }
}
