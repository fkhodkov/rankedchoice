package info.fkhodkov.rankedchoice.stv.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;

class TransferMethodTest {

    @Test
    void bothMethodsAgreeWhenArrivalValueIsOne() {
        Fraction surplus = Fraction.of(10);
        Fraction candidateVotes = Fraction.of(23);
        Fraction arrivalValue = Fraction.ONE;
        TruncateDecimal rounding = new TruncateDecimal(2);

        assertThat(FractionalTransfer.INSTANCE.transferValue(surplus, candidateVotes, arrivalValue, rounding))
                .isEqualTo(WigmTransfer.INSTANCE.transferValue(surplus, candidateVotes, arrivalValue, rounding));
    }

    @Test
    void bothMethodsAgreeUnderExactRoundingRegardlessOfArrivalValue() {
        // Without rounding getting in the way, round(x*V)==round(x)*V trivially, for any V.
        Fraction surplus = Fraction.of(13, 10);
        Fraction candidateVotes = Fraction.of(143, 10);
        Fraction arrivalValue = Fraction.of(43, 100);

        assertThat(FractionalTransfer.INSTANCE.transferValue(surplus, candidateVotes, arrivalValue, Exact.INSTANCE))
                .isEqualTo(WigmTransfer.INSTANCE.transferValue(surplus, candidateVotes, arrivalValue, Exact.INSTANCE));
    }

    @Test
    void methodsDivergeForANonUnitArrivalValueUnderTruncation() {
        // surplus/candidateVotes = 13/143 = 1/11 = 0.0909... -> truncates to 0.09 either way.
        Fraction surplus = Fraction.of(13, 10);
        Fraction candidateVotes = Fraction.of(143, 10);
        Fraction arrivalValue = Fraction.of(43, 100);
        TruncateDecimal rounding = new TruncateDecimal(2);

        Fraction fractional = FractionalTransfer.INSTANCE.transferValue(surplus, candidateVotes, arrivalValue, rounding);
        Fraction wigm = WigmTransfer.INSTANCE.transferValue(surplus, candidateVotes, arrivalValue, rounding);

        // FractionalTransfer: arrivalValue * truncate(surplus/candidateVotes, 2) = 43/100 * 9/100.
        assertThat(fractional).isEqualTo(Fraction.of(43, 100).multiply(Fraction.of(9, 100)));
        // WigmTransfer: truncate(surplus*arrivalValue/candidateVotes, 2) = truncate(43/1100, 2) = 0.03.
        assertThat(wigm).isEqualTo(Fraction.of(3, 100));
        assertThat(fractional).isNotEqualTo(wigm);
    }

    @Test
    void zeroCandidateVotesReturnsZeroForBothMethods() {
        Fraction surplus = Fraction.ZERO;
        Fraction candidateVotes = Fraction.ZERO;
        assertThat(FractionalTransfer.INSTANCE.transferValue(surplus, candidateVotes, Fraction.ONE, Exact.INSTANCE))
                .isEqualTo(Fraction.ZERO);
        assertThat(WigmTransfer.INSTANCE.transferValue(surplus, candidateVotes, Fraction.ONE, Exact.INSTANCE))
                .isEqualTo(Fraction.ZERO);
    }
}
