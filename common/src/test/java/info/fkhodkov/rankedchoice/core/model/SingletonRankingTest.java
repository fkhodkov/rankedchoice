package info.fkhodkov.rankedchoice.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link SingletonRanking.Table#ranking}: the checks a ballot made from its ranking relies on. */
class SingletonRankingTest {

    private final Candidate alice = Candidate.of("alice");
    private final Candidate bob = Candidate.of("bob");

    @Test
    void ranksTheCandidatesAtTheIndices() {
        SingletonRanking.Table table = new SingletonRanking.Table(List.of(alice, bob));

        assertThat(table.ranking(new int[] {1, 0})).containsExactly(Set.of(bob), Set.of(alice));
    }

    @Test
    void refusesAnIndexOutsideTheTable() {
        SingletonRanking.Table one = new SingletonRanking.Table(List.of(alice));

        // Index 1 fits the table's bit set, so it once passed and failed only when the ballot was read.
        for (int index : new int[] {1, 64, -1}) {
            assertThatThrownBy(() -> one.ranking(new int[] {index}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("candidate index " + index + " is outside the 1 candidates (0 to 0)");
        }
    }

    @Test
    void refusesACandidateTwice() {
        SingletonRanking.Table table = new SingletonRanking.Table(List.of(alice, bob));

        assertThatThrownBy(() -> table.ranking(new int[] {0, 1, 0}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidate alice appears more than once in the same ballot");
    }
}
