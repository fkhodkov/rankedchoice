package info.fkhodkov.rankedchoice.schulzestv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

class SchulzeStvCounterTest {

    private static final Candidate A = Candidate.of("A");
    private static final Candidate B = Candidate.of("B");
    private static final Candidate C = Candidate.of("C");
    private static final Candidate D = Candidate.of("D");

    private static Ballot ballot(int count, Candidate... ranking) {
        List<Set<Candidate>> ranks = new ArrayList<>();
        for (Candidate candidate : ranking) {
            ranks.add(Set.of(candidate));
        }
        return new Ballot(ranks, BigInteger.valueOf(count));
    }

    /**
     * With no ballots at all (not even blank ones) every support is 0, as with only blank
     * ballots, and the proportional ranking falls back on its tie-break: the declared order.
     */
    @Test
    void noBallotsAtAllGiveEverySupportZero() {
        SchulzeStvResult committee = SchulzeStvCounter.count(List.of(A, B, C), List.of(), 2);
        assertThat(committee.supports()).allSatisfy(s -> assertThat(s.support()).isEqualTo(Fraction.ZERO));

        SchulzeProportionalResult list = SchulzeProportionalRanking.count(List.of(A, B, C), List.of());
        assertThat(list.ranking()).containsExactly(A, B, C);
        assertThat(list.places()).allSatisfy(place -> assertThat(place.comparisons().values())
                .allSatisfy(row -> assertThat(row.values()).containsOnly(Fraction.ZERO)));
    }

    private static Fraction support(SchulzeStvResult result, Set<Candidate> committee, Candidate against) {
        return result.supports().stream()
                .filter(support -> support.committee().equals(committee) && support.against().equals(against))
                .findFirst().orElseThrow().support();
    }

    @Test
    void aSupportIsTheLargestQuotaEveryMemberCanHaveSeparately() {
        // All 10 prefer both A and B to C, but each voter counts once: 5 apiece.
        SchulzeStvResult result = SchulzeStvCounter.count(List.of(A, B, C), List.of(ballot(10, A, B, C)), 2);

        assertThat(support(result, Set.of(A, B), C)).isEqualTo(Fraction.of(5));
    }

    @Test
    void twoOpposedFactionsEachGetASeatRatherThanTheCompromise() {
        // N[{A,C}; B] = 1 (one voter each), but N[{A,B}; C] = N[{B,C}; A] = 1/2.
        SchulzeStvResult result = SchulzeStvCounter.count(List.of(A, B, C),
                List.of(ballot(1, A, B, C), ballot(1, C, B, A)), 2);

        assertThat(support(result, Set.of(A, C), B)).isEqualTo(Fraction.ONE);
        assertThat(support(result, Set.of(A, B), C)).isEqualTo(Fraction.of(1, 2));
        assertThat(result.potentialWinners()).containsExactly(Set.of(A, C));
        assertThat(result.winners()).containsExactly(Set.of(A, C));
    }

    @Test
    void anIndifferentVoterIsSharedInProportionToTheOthers() {
        // Section 8.1.1's example: 56 prefer A, 44 prefer B, so a blank voter adds 0.56 to A.
        SchulzeStvResult result = SchulzeStvCounter.count(List.of(A, B),
                List.of(ballot(56, A, B), ballot(44, B, A), new Ballot(List.of(), BigInteger.ONE)), 1);

        assertThat(support(result, Set.of(A), B)).isEqualTo(Fraction.of(5656, 100));
        assertThat(support(result, Set.of(B), A)).isEqualTo(Fraction.of(4444, 100));
        assertThat(result.winners()).containsExactly(Set.of(A));
    }

    @Test
    void withNobodyToCopyEveryOrderOfTheTiedCandidatesIsEquallyLikely() {
        // Six blank voters: in a random order of A, B, C, both A and B beat C one time in
        // three, so the pair can share 2 of them, and every committee ties.
        Ballot blank = new Ballot(List.of(), BigInteger.valueOf(6));
        SchulzeStvResult result = SchulzeStvCounter.count(List.of(A, B, C), List.of(blank), 2);

        assertThat(support(result, Set.of(A, B), C)).isEqualTo(Fraction.of(2));
        assertThat(result.potentialWinners()).hasSize(3);
        assertThat(result.winners()).hasSize(3);
    }

    @Test
    void theSingleWinnerRankingBreaksATieBetweenPotentialWinners() {
        // {B,D} and {C,D} both go unbeaten; C beats B 2-1 head to head, so {C,D} wins.
        // Found and checked with an independent prototype of the paper's definition.
        SchulzeStvResult result = SchulzeStvCounter.count(List.of(A, B, C, D),
                List.of(ballot(1, B, C, D), ballot(1, D, C, B), ballot(1, D, A, C, B)), 2);

        assertThat(result.potentialWinners()).containsExactlyInAnyOrder(Set.of(B, D), Set.of(C, D));
        assertThat(result.winners()).containsExactly(Set.of(C, D));
    }

    @Test
    void fillingEverySeatElectsEveryone() {
        SchulzeStvResult result = SchulzeStvCounter.count(List.of(A, B), List.of(ballot(1, A)), 2);

        assertThat(result.winners()).containsExactly(Set.of(A, B));
        assertThat(result.supports()).isEmpty();
    }

    @Test
    void refusesTooManyCommittees() {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            candidates.add(Candidate.of("C" + i));
        }

        assertThatThrownBy(() -> SchulzeStvCounter.count(candidates, List.of(), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C(30, 10) = 30045015 possible committees exceeds this implementation's cap");
    }
}
