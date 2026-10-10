package info.fkhodkov.rankedchoice.stv;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Candidate;

class StvCounterValidationTest {

    private static final Candidate A = Candidate.of("A");
    private static final Candidate B = Candidate.of("B");

    @Test
    void acceptsValidInput() {
        assertThatCode(() -> StvCounterValidation.validate(List.of(A, B), 1)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveSeats() {
        assertThatThrownBy(() -> StvCounterValidation.validate(List.of(A), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StvCounterValidation.validate(List.of(A), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMoreSeatsThanCandidates() {
        assertThatThrownBy(() -> StvCounterValidation.validate(List.of(A), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
