package info.fkhodkov.rankedchoice.stv;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

class StvCounterValidationTest {

    private static final Candidate A = Candidate.of("A");
    private static final Candidate B = Candidate.of("B");

    @Test
    void acceptsValidInput() {
        Ballot ballot = new Ballot(List.of(Set.of(A), Set.of(B)), BigInteger.ONE);
        assertThatCode(() -> StvCounterValidation.validate(List.of(A, B), List.of(ballot), 1))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveSeats() {
        assertThatThrownBy(() -> StvCounterValidation.validate(List.of(A), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StvCounterValidation.validate(List.of(A), List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMoreSeatsThanCandidates() {
        assertThatThrownBy(() -> StvCounterValidation.validate(List.of(A), List.of(), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTiedRankings() {
        Ballot tiedBallot = new Ballot(List.of(Set.of(A, B)), BigInteger.ONE);
        assertThatThrownBy(() -> StvCounterValidation.validate(List.of(A, B), List.of(tiedBallot), 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
