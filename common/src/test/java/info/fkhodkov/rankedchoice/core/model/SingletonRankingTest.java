package info.fkhodkov.rankedchoice.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link SingletonRanking.Table}: the checks a ballot made from its ranking relies on. */
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

    @Test
    void refusesATableListingACandidateTwice() {
        // Indices 0 and 1 differ, so ranking() would let them by, and the ballot doesn't look again.
        assertThatThrownBy(() -> new SingletonRanking.Table(List.of(alice, alice)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidate alice is listed twice");
    }

    @Test
    void aBallotRefusesTheSameRankingsEitherWay() {
        SingletonRanking.Table table = new SingletonRanking.Table(List.of(alice, bob));
        List<Set<Candidate>> compact = table.ranking(new int[] {1, 0});

        assertThat(new Ballot(compact, BigInteger.ONE)).isEqualTo(new Ballot(List.of(Set.of(bob), Set.of(alice)),
                BigInteger.ONE));
        assertThatThrownBy(() -> table.ranking(new int[] {0, 0}))
                .hasMessage("candidate alice appears more than once in the same ballot");
        assertThatThrownBy(() -> new Ballot(List.of(Set.of(alice), Set.of(alice)), BigInteger.ONE))
                .hasMessage("candidate alice appears more than once in the same ballot");
    }
}
