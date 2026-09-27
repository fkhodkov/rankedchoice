package info.fkhodkov.rankedchoice.stv;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.TransferMethod;
import info.fkhodkov.rankedchoice.stv.transfer.WigmTransfer;

class StvCounterValidationTest {

    private static final Candidate A = Candidate.of("A");
    private static final Candidate B = Candidate.of("B");

    private static StvRuleset ruleset(TransferMethod transferMethod) {
        return new StvRuleset(DroopQuota.INSTANCE, transferMethod, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(A, B)));
    }

    @Test
    void acceptsValidInput() {
        Ballot ballot = new Ballot(List.of(Set.of(A), Set.of(B)), BigInteger.ONE);
        assertThatCode(() -> StvCounterValidation.validate(
                List.of(A, B), List.of(ballot), 1, ruleset(WigmTransfer.INSTANCE)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveSeats() {
        StvRuleset ruleset = ruleset(WigmTransfer.INSTANCE);
        assertThatThrownBy(() -> StvCounterValidation.validate(List.of(A), List.of(), 0, ruleset))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StvCounterValidation.validate(List.of(A), List.of(), -1, ruleset))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMoreSeatsThanCandidates() {
        assertThatThrownBy(() -> StvCounterValidation.validate(
                List.of(A), List.of(), 2, ruleset(WigmTransfer.INSTANCE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTiedRankingsUnderNonFractionalTransferMethods() {
        Ballot tiedBallot = new Ballot(List.of(Set.of(A, B)), BigInteger.ONE);
        assertThatThrownBy(() -> StvCounterValidation.validate(
                List.of(A, B), List.of(tiedBallot), 1, ruleset(WigmTransfer.INSTANCE)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsTiedRankingsUnderFractionalTransfer() {
        Ballot tiedBallot = new Ballot(List.of(Set.of(A, B)), BigInteger.ONE);
        assertThatCode(() -> StvCounterValidation.validate(
                List.of(A, B), List.of(tiedBallot), 1, ruleset(FractionalTransfer.INSTANCE)))
                .doesNotThrowAnyException();
    }
}
