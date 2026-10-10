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
    void nonTransferablePapersAreExcludedFromTheRatioAndStayWithTheElectedCandidate() {
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
        // The non-transferable papers aren't transferred: they stay with the elected candidate
        // as part of the quota, as in the ERS97 rulebook's model election (Smith's 11 such
        // papers are not non-transferable votes there; only the 0.59 lost to rounding is).
        assertThat(result).containsOnly(Map.entry(1, Fraction.of(1, 2)));
    }

    @Test
    void allPapersNonTransferableTransfersNothing() {
        HeldParcel nonTransferable = new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(5), false);

        Map<Integer, Fraction> result = LastParcelTransfer.INSTANCE.computeTransferValues(
                Fraction.of(10), List.of(nonTransferable), Exact.INSTANCE);

        assertThat(result).isEmpty();
    }

    @Test
    void papersFromAnEarlierSurplusMoveAtTheTransferValueNotAShareOfTheirOwn() {
        // 20 papers that arrived at 1/4 each (5 votes); a surplus of 2 gives each 2/20 = 1/10,
        // passing on the whole surplus -- not 1/4 x 1/10, which would pass on only a quarter.
        HeldParcel fractional = new HeldParcel(1, 3, Fraction.of(1, 4), BigInteger.valueOf(20), true);

        Map<Integer, Fraction> result = LastParcelTransfer.INSTANCE.computeTransferValues(
                Fraction.of(2), List.of(fractional), Exact.INSTANCE);

        assertThat(result).containsOnly(Map.entry(1, Fraction.of(1, 10)));
    }

    @Test
    void aSurplusAtLeastTheTransferablePapersValueMovesThemAtTheirOwnValue() {
        // The same 20 papers worth 5 votes, but a surplus of 6: 6/20 would give each more
        // than it's worth, so each keeps its 1/4. The engine counts the other 1 as exhausted.
        HeldParcel fractional = new HeldParcel(1, 3, Fraction.of(1, 4), BigInteger.valueOf(20), true);

        Map<Integer, Fraction> result = LastParcelTransfer.INSTANCE.computeTransferValues(
                Fraction.of(6), List.of(fractional), Exact.INSTANCE);

        assertThat(result).containsOnly(Map.entry(1, Fraction.of(1, 4)));
    }

    @Test
    void emptyParcelsGiveAnEmptyResult() {
        assertThat(LastParcelTransfer.INSTANCE.computeTransferValues(Fraction.of(5), List.of(), Exact.INSTANCE))
                .isEmpty();
    }

    @Test
    void zeroVoteLastParcelGivesAnEmptyResult() {
        HeldParcel zeroParcel = new HeldParcel(1, 1, Fraction.ZERO, BigInteger.ZERO, true);
        assertThat(LastParcelTransfer.INSTANCE.computeTransferValues(
                Fraction.of(5), List.of(zeroParcel), Exact.INSTANCE)).isEmpty();
    }
}
