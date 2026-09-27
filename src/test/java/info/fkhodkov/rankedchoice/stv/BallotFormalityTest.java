package info.fkhodkov.rankedchoice.stv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

class BallotFormalityTest {

    private static final Candidate A = Candidate.of("A");
    private static final Candidate B = Candidate.of("B");
    private static final Candidate C = Candidate.of("C");
    private static final Candidate D = Candidate.of("D");

    private static Ballot ballot(int count, List<Set<Candidate>> ranking) {
        return new Ballot(ranking, BigInteger.valueOf(count));
    }

    /**
     * Each ballot is set aside for the first reason that applies: blank as cast, then emptied
     * by the tie policy, then too short. A ballot a tie leaves empty counts as a tie, not as
     * too short, even though it's both.
     */
    @Test
    void setsAsideEachBallotForTheFirstReasonThatApplies() {
        Ballot formal = ballot(10, List.of(Set.of(A), Set.of(B), Set.of(C)));
        Ballot formalAfterTruncation = ballot(9, List.of(Set.of(A), Set.of(B), Set.of(C, D)));
        List<Ballot> ballots = List.of(
                formal,
                ballot(1, List.of()),
                ballot(2, List.of(Set.of(A, B), Set.of(C))),
                ballot(3, List.of(Set.of(A), Set.of(B, C))),
                ballot(4, List.of(Set.of(D))),
                formalAfterTruncation);

        BallotFormality.FormalBallots result = BallotFormality.apply(ballots, TiedRankingPolicy.TRUNCATE, 2, 4);

        assertThat(result.ballots()).containsExactly(formal, ballot(9, List.of(Set.of(A), Set.of(B))));
        assertThat(result.informal()).isEqualTo(new InformalBallots(
                BigInteger.ONE, BigInteger.valueOf(2), BigInteger.valueOf(3 + 4)));
        assertThat(result.informal().total()).isEqualTo(BigInteger.valueOf(10));
        assertThat(result.informal().describe()).isEqualTo("10 (blank: 1, tied rankings: 2, too few preferences: 7)");
    }

    @Test
    void skipOnlyVoidsABallotWhoseEveryRankIsTied() {
        List<Ballot> ballots = List.of(
                ballot(5, List.of(Set.of(A))),
                ballot(2, List.of(Set.of(A, B))),
                ballot(3, List.of(Set.of(A, B), Set.of(C))));

        BallotFormality.FormalBallots result = BallotFormality.apply(ballots, TiedRankingPolicy.SKIP, 0, 3);

        assertThat(result.ballots()).containsExactly(ballots.getFirst(), ballot(3, List.of(Set.of(C))));
        assertThat(result.informal().describe()).isEqualTo("2 (tied rankings: 2)");
    }

    @Test
    void refusesToCountWhenNothingIsLeft() {
        List<Ballot> ballots = List.of(ballot(2, List.of(Set.of(A, B))), ballot(1, List.of()));

        assertThatThrownBy(() -> BallotFormality.apply(ballots, TiedRankingPolicy.TRUNCATE, 0, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No valid votes");
    }
}
