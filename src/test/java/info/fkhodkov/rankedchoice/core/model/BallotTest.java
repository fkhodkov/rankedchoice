package info.fkhodkov.rankedchoice.core.model;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BallotTest {

    private final Candidate alice = Candidate.of("alice");
    private final Candidate bob = Candidate.of("bob");
    private final Candidate carol = Candidate.of("carol");

    @Test
    void simpleRankingPreservesOrder() {
        Ballot ballot = new Ballot(
                List.of(Set.of(alice), Set.of(bob), Set.of(carol)),
                BigInteger.ONE);

        assertThat(ballot.firstPreference()).containsExactly(alice);
        assertThat(ballot.lastPreference()).containsExactly(carol);
        assertThat(ballot.ranking()).hasSize(3);
    }

    @Test
    void supportsTiedRanks() {
        Ballot ballot = new Ballot(
                List.of(Set.of(alice, bob), Set.of(carol)),
                BigInteger.TWO);

        assertThat(ballot.firstPreference()).containsExactlyInAnyOrder(alice, bob);
        assertThat(ballot.count()).isEqualTo(BigInteger.TWO);
    }

    @Test
    void blankBallotHasEmptyRanking() {
        Ballot ballot = new Ballot(List.of(), BigInteger.ONE);
        assertThat(ballot.isBlank()).isTrue();
    }

    @Test
    void rejectsNonPositiveCount() {
        assertThatThrownBy(() -> new Ballot(List.of(Set.of(alice)), BigInteger.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyRank() {
        assertThatThrownBy(() -> new Ballot(List.of(Set.of()), BigInteger.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCandidateAppearingTwice() {
        assertThatThrownBy(() -> new Ballot(List.of(Set.of(alice), Set.of(alice)), BigInteger.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rankingIsImmutable() {
        Ballot ballot = new Ballot(List.of(Set.of(alice)), BigInteger.ONE);
        assertThatThrownBy(() -> ballot.ranking().add(Set.of(bob)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ballot.firstPreference().add(bob))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
