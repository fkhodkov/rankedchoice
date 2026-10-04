package info.fkhodkov.rankedchoice.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CandidateTest {

    @Test
    void blankNameDefaultsToId() {
        assertThat(new Candidate("1", " ").name()).isEqualTo("1");
    }

    @Test
    void blankIdThrows() {
        assertThatThrownBy(() -> new Candidate(" ", "Alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofUsesIdAsName() {
        assertThat(Candidate.of("alice")).isEqualTo(new Candidate("alice", "alice"));
    }
}
