package info.fkhodkov.rankedchoice.stv.tiebreak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;

class TieBreakRuleTest {

    private static final Candidate A = Candidate.of("A");
    private static final Candidate B = Candidate.of("B");
    private static final Candidate C = Candidate.of("C");

    private static RoundRecord round(int number, Map<Candidate, Fraction> tallies) {
        return new RoundRecord(number, tallies, new RoundAction.Eliminated(A), Fraction.ZERO);
    }

    // --- DeclaredOrderTieBreak ---

    @Test
    void declaredOrderPicksEarliestInOrderForBothRoles() {
        TieBreakRule rule = new DeclaredOrderTieBreak(List.of(A, B, C));
        assertThat(rule.candidateToEliminate(List.of(C, B), List.of())).isEqualTo(B);
        assertThat(rule.candidateToElectFirst(List.of(C, B), List.of())).isEqualTo(B);
    }

    @Test
    void declaredOrderRejectsEmptyTie() {
        TieBreakRule rule = new DeclaredOrderTieBreak(List.of(A, B));
        assertThatThrownBy(() -> rule.candidateToEliminate(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declaredOrderRejectsCandidateNotInOrder() {
        TieBreakRule rule = new DeclaredOrderTieBreak(List.of(A, B));
        assertThatThrownBy(() -> rule.candidateToEliminate(List.of(C), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- PreviousRoundVotesTieBreak ---

    @Test
    void previousRoundVotesUsesMostRecentDifferingRound() {
        List<RoundRecord> priorRounds = List.of(
                round(1, Map.of(B, Fraction.of(2), C, Fraction.of(2))),
                round(2, Map.of(B, Fraction.of(3), C, Fraction.of(1))));
        TieBreakRule rule = new PreviousRoundVotesTieBreak(new DeclaredOrderTieBreak(List.of(A, B, C)));

        assertThat(rule.candidateToEliminate(List.of(B, C), priorRounds)).isEqualTo(C);
        assertThat(rule.candidateToElectFirst(List.of(B, C), priorRounds)).isEqualTo(B);
    }

    @Test
    void previousRoundVotesFallsBackWhenNeverDiffered() {
        List<RoundRecord> priorRounds = List.of(round(1, Map.of(B, Fraction.of(2), C, Fraction.of(2))));
        TieBreakRule rule = new PreviousRoundVotesTieBreak(new DeclaredOrderTieBreak(List.of(A, B, C)));

        assertThat(rule.candidateToEliminate(List.of(C, B), priorRounds)).isEqualTo(B);
    }

    @Test
    void previousRoundVotesFallsBackWhenNoHistory() {
        TieBreakRule rule = new PreviousRoundVotesTieBreak(new DeclaredOrderTieBreak(List.of(A, B, C)));
        assertThat(rule.candidateToEliminate(List.of(C, B), List.of())).isEqualTo(B);
    }

    // --- FirstPreferenceCountTieBreak ---

    @Test
    void firstPreferenceCountUsesRoundOneTallies() {
        List<RoundRecord> priorRounds = List.of(
                round(1, Map.of(B, Fraction.of(5), C, Fraction.of(2))),
                round(2, Map.of(B, Fraction.of(4), C, Fraction.of(3))));
        TieBreakRule rule = new FirstPreferenceCountTieBreak(new DeclaredOrderTieBreak(List.of(A, B, C)));

        assertThat(rule.candidateToEliminate(List.of(B, C), priorRounds)).isEqualTo(C);
        assertThat(rule.candidateToElectFirst(List.of(B, C), priorRounds)).isEqualTo(B);
    }

    @Test
    void firstPreferenceCountFallsBackWhenTiedAtRoundOneToo() {
        List<RoundRecord> priorRounds = List.of(round(1, Map.of(B, Fraction.of(2), C, Fraction.of(2))));
        TieBreakRule rule = new FirstPreferenceCountTieBreak(new DeclaredOrderTieBreak(List.of(A, B, C)));

        assertThat(rule.candidateToEliminate(List.of(C, B), priorRounds)).isEqualTo(B);
    }

    @Test
    void firstPreferenceCountFallsBackWhenNoHistory() {
        TieBreakRule rule = new FirstPreferenceCountTieBreak(new DeclaredOrderTieBreak(List.of(A, B, C)));
        assertThat(rule.candidateToEliminate(List.of(C, B), List.of())).isEqualTo(B);
    }

    // --- SeededRandomTieBreak ---

    @Test
    void seededRandomAlwaysPicksFromTheTiedList() {
        TieBreakRule rule = new SeededRandomTieBreak(42);
        for (int i = 0; i < 20; i++) {
            assertThat(rule.candidateToEliminate(List.of(A, B, C), List.of())).isIn(A, B, C);
        }
    }

    @Test
    void seededRandomIsReproducibleForTheSameSeed() {
        TieBreakRule first = new SeededRandomTieBreak(7);
        TieBreakRule second = new SeededRandomTieBreak(7);
        List<Candidate> tied = List.of(A, B, C);

        for (int i = 0; i < 10; i++) {
            assertThat(first.candidateToEliminate(tied, List.of()))
                    .isEqualTo(second.candidateToEliminate(tied, List.of()));
        }
    }

    @Test
    void seededRandomRejectsEmptyTie() {
        TieBreakRule rule = new SeededRandomTieBreak(1);
        assertThatThrownBy(() -> rule.candidateToEliminate(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
