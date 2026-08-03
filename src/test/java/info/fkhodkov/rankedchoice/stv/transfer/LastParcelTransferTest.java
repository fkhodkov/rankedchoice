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
        HeldParcel earlier = new HeldParcel(1, /* arrivalParcelId */ 1, Fraction.ONE, BigInteger.valueOf(10), true);
        HeldParcel last = new HeldParcel(2, /* arrivalParcelId */ 5, Fraction.ONE, BigInteger.valueOf(20), true);
        Fraction surplus = Fraction.of(5);

        Map<Integer, Fraction> result = LastParcelTransfer.INSTANCE.computeTransferValues(
                surplus, List.of(earlier, last), Exact.INSTANCE);

        // ratio = surplus / lastParcelVotes = 5/20 = 1/4, applied to the last parcel's own value (1).
        assertThat(result).containsOnlyKeys(2).containsEntry(2, Fraction.of(1, 4));
    }

    @Test
    void ratioUsesTheLastParcelsOwnTotalNotTheWholeCandidates() {
        HeldParcel earlier = new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(90), true);
        HeldParcel last = new HeldParcel(2, 2, Fraction.ONE, BigInteger.valueOf(10), true);
        Fraction surplus = Fraction.of(5);

        // If the ratio wrongly used the whole candidate (100), it would be 5/100 = 0.05;
        // using just the last parcel's own 10 votes, it's 5/10 = 0.5.
        Map<Integer, Fraction> result = LastParcelTransfer.INSTANCE.computeTransferValues(
                surplus, List.of(earlier, last), Exact.INSTANCE);

        assertThat(result).containsOnlyKeys(2).containsEntry(2, Fraction.of(1, 2));
    }

    @Test
    void appliesRoundingToTheLastParcelRatio() {
        HeldParcel last = new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(23), true);
        Fraction surplus = Fraction.of(10);

        Map<Integer, Fraction> result = LastParcelTransfer.INSTANCE.computeTransferValues(
                surplus, List.of(last), new TruncateDecimal(2));

        // 10/23 = 0.434782... -> truncates to 0.43.
        assertThat(result).containsEntry(1, Fraction.of(43, 100));
    }

    @Test
    void nonTransferablePapersAreExcludedFromTheRatioAndKeepTheirOwnValue() {
        // 20 papers with a further preference, 5 without (would become non-transferable).
        // Confirmed against both the ACT Electoral Act Schedule 4 ("CP means the number of
        // ballot papers ... that specify a next available preference") and opavote's citation
        // of ERS97 rule 5.3.7 ("the number of transferable papers") -- real ACT election data
        // (2020 Kurrajong) independently confirmed this against our engine's own output.
        HeldParcel transferable = new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(20), true);
        HeldParcel nonTransferable = new HeldParcel(2, 1, Fraction.ONE, BigInteger.valueOf(5), false);
        Fraction surplus = Fraction.of(10);

        Map<Integer, Fraction> result = LastParcelTransfer.INSTANCE.computeTransferValues(
                surplus, List.of(transferable, nonTransferable), Exact.INSTANCE);

        // ratio = surplus / transferablePapers = 10/20 = 1/2 -- NOT 10/25, which is what
        // including the non-transferable papers in the denominator would wrongly give.
        assertThat(result)
                .containsEntry(1, Fraction.of(1, 2))
                // The non-transferable papers carry their own existing value (1) forward
                // unscaled -- they never entered the ratio, so they're not reduced by it.
                .containsEntry(2, Fraction.ONE);
    }

    @Test
    void allPapersNonTransferableGivesNoRatioButStillReturnsTheirOwnValue() {
        HeldParcel nonTransferable = new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(5), false);

        Map<Integer, Fraction> result = LastParcelTransfer.INSTANCE.computeTransferValues(
                Fraction.of(10), List.of(nonTransferable), Exact.INSTANCE);

        assertThat(result).containsOnly(Map.entry(1, Fraction.ONE));
    }

    @Test
    void emptyParcelsGiveAnEmptyResult() {
        assertThat(LastParcelTransfer.INSTANCE.computeTransferValues(Fraction.of(5), List.of(), Exact.INSTANCE))
                .isEmpty();
    }

    @Test
    void zeroVoteLastParcelGivesAnEmptyResult() {
        HeldParcel zeroParcel = new HeldParcel(1, 1, Fraction.ZERO, BigInteger.ZERO, true);
        assertThat(LastParcelTransfer.INSTANCE.computeTransferValues(Fraction.of(5), List.of(zeroParcel), Exact.INSTANCE))
                .isEmpty();
    }
}
