package info.fkhodkov.rankedchoice.stv.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;

class UnweightedExclusiveTransferTest {

    private static final UnweightedExclusiveTransfer TASMANIA = new UnweightedExclusiveTransfer(6);

    @Test
    void dividesTheSurplusOverEveryPaperOfTheLastParcel() {
        // Tasmania's Schedule 4, clause 8(4)(b): "the total number of ballot papers mentioned in
        // paragraph (a)", every paper of the last transfer, not only those with a next preference.
        HeldParcel earlier = new HeldParcel(1, 1, Fraction.ONE, BigInteger.valueOf(90), true);
        HeldParcel transferable = new HeldParcel(2, 5, Fraction.ONE, BigInteger.valueOf(20), true);
        HeldParcel nonTransferable = new HeldParcel(3, 5, Fraction.ONE, BigInteger.valueOf(5), false);

        Map<Integer, Fraction> result = TASMANIA.computeTransferValues(
                Fraction.of(10), List.of(earlier, transferable, nonTransferable), new TruncateDecimal(0));

        // 10 / 25 = 0.4 for each of the last parcel's papers (those with no next preference
        // exhaust at it); the earlier parcel stays with the elected candidate.
        assertThat(result).containsOnly(Map.entry(2, Fraction.of(2, 5)), Map.entry(3, Fraction.of(2, 5)));
    }

    @Test
    void truncatesTheTransferValueToItsOwnPlacesWhateverThePapersWereWorth() {
        // Papers that arrived at a third of a vote still move at surplus / papers.
        HeldParcel last = new HeldParcel(1, 1, Fraction.of(1, 3), BigInteger.valueOf(23), true);

        Map<Integer, Fraction> result = TASMANIA.computeTransferValues(
                Fraction.of(10, 3), List.of(last), new TruncateDecimal(0));

        // (10/3) / 23 = 0.144927536... -> 0.144927, not the ruleset's 0 places.
        assertThat(result).containsOnly(Map.entry(1, Fraction.of(144927, 1000000)));
    }

    @Test
    void roundsEachCandidatesGainPerStep() {
        assertThat(TASMANIA.roundsGainsPerStep()).isTrue();
    }

    @Test
    void refusesNegativePlaces() {
        assertThatThrownBy(() -> new UnweightedExclusiveTransfer(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
