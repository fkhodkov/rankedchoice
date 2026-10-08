package info.fkhodkov.rankedchoice.borda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

class BordaMethodTest {

    @Test
    void classicBordaScoresAFullyRankedFieldByPosition() {
        // 3 candidates: a first preference is worth 2, second worth 1, last worth 0.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Ballot> ballots = List.of(
                ballot(5, a, b, c),
                ballot(3, b, c, a));

        BordaResult result = BordaMethod.count(List.of(a, b, c), ballots, ClassicBorda.INSTANCE);

        // A: 5*2 + 3*0 = 10. B: 5*1 + 3*2 = 11. C: 5*0 + 3*1 = 3.
        assertThat(result.scores()).isEqualTo(Map.of(a, Fraction.of(10), b, Fraction.of(11), c, Fraction.of(3)));
        assertThat(result.rankingBestToWorst()).containsExactly(b, a, c);
    }

    @Test
    void aCandidateLeftOffABallotEntirelyScoresNothingFromIt() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        // Only ranks A then B -- C is left off entirely, not just ranked last.
        List<Ballot> ballots = List.of(ballot(1, a, b));

        BordaResult result = BordaMethod.count(List.of(a, b, c), ballots, ClassicBorda.INSTANCE);

        assertThat(result.scores().get(c)).isEqualTo(Fraction.ZERO);
    }

    @Test
    void tiedCandidatesEachGetTheAverageOfTheirTiedPositionsScores() {
        // 4 candidates under classic Borda: positions are worth 3,2,1,0. A single ballot ranks
        // A first, then B and C tied for 2nd/3rd, then D last -- B and C should each get
        // (2+1)/2 = 1.5, not 2 or 1.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        Ballot ballot = new Ballot(List.of(Set.of(a), Set.of(b, c), Set.of(d)), BigInteger.ONE);

        BordaResult result = BordaMethod.count(List.of(a, b, c, d), List.of(ballot), ClassicBorda.INSTANCE);

        assertThat(result.scores().get(a)).isEqualTo(Fraction.of(3));
        assertThat(result.scores().get(b)).isEqualTo(Fraction.of(3, 2));
        assertThat(result.scores().get(c)).isEqualTo(Fraction.of(3, 2));
        assertThat(result.scores().get(d)).isEqualTo(Fraction.ZERO);
    }

    @Test
    void dowdallScoringWeighsByPositionOnlyNotByFieldSize() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        List<Ballot> ballots = List.of(ballot(6, a, b));

        BordaResult result = BordaMethod.count(List.of(a, b), ballots, DowdallScoring.INSTANCE);

        assertThat(result.scores().get(a)).isEqualTo(Fraction.of(6));
        assertThat(result.scores().get(b)).isEqualTo(Fraction.of(3));
    }

    @Test
    void modifiedBordaUsesTheGivenStartWeightAndStep() {
        // startWeight=10, step=3: rank 0 -> 10, rank 1 -> 7, rank 2 -> 4.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Ballot> ballots = List.of(ballot(2, a, b, c));
        ModifiedBorda rule = new ModifiedBorda(Fraction.of(10), Fraction.of(3));

        BordaResult result = BordaMethod.count(List.of(a, b, c), ballots, rule);

        assertThat(result.scores()).isEqualTo(Map.of(a, Fraction.of(20), b, Fraction.of(14), c, Fraction.of(8)));
    }

    @Test
    void aPartialBallotScoresByItsOwnPositionsNotByHowManyItRanks() {
        // 3 candidates declared (classic Borda weights 2,1,0 by position). One ballot fully
        // ranks A,B,C; a second ballot ranks only B (A and C left off entirely) -- B's single
        // entry is still position 0 on that ballot, worth 2, same as any other first
        // preference, regardless of the ballot only naming one candidate.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Ballot> ballots = List.of(
                ballot(4, a, b, c),
                ballot(6, b));

        BordaResult result = BordaMethod.count(List.of(a, b, c), ballots, ClassicBorda.INSTANCE);

        // A: 4*2 = 8. B: 4*1 + 6*2 = 16. C: 4*0 = 0.
        assertThat(result.scores()).isEqualTo(Map.of(a, Fraction.of(8), b, Fraction.of(16), c, Fraction.ZERO));
    }

    @Test
    void aThreeWayTieAveragesAllThreeSpannedPositions() {
        // 3 candidates all tied in one single rank group -- classic Borda weights for a
        // 3-candidate field are 2,1,0, so the tied average is (2+1+0)/3 = 1 each.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Ballot ballot = new Ballot(List.of(Set.of(a, b, c)), BigInteger.valueOf(5));

        BordaResult result = BordaMethod.count(List.of(a, b, c), List.of(ballot), ClassicBorda.INSTANCE);

        assertThat(result.scores()).isEqualTo(Map.of(a, Fraction.of(5), b, Fraction.of(5), c, Fraction.of(5)));
    }

    @Test
    void aSingleCandidateElectionAlwaysScoresZeroUnderClassicBorda() {
        // n=1: classic Borda's first-preference weight is n-1 = 0, regardless of turnout.
        Candidate a = Candidate.of("A");
        List<Ballot> ballots = List.of(ballot(100, a));

        BordaResult result = BordaMethod.count(List.of(a), ballots, ClassicBorda.INSTANCE);

        assertThat(result.scores()).isEqualTo(Map.of(a, Fraction.ZERO));
        assertThat(result.rankingBestToWorst()).containsExactly(a);
    }

    @Test
    void aBlankBallotContributesNothingToAnyCandidate() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Ballot blank = new Ballot(List.of(), BigInteger.TEN);
        List<Ballot> ballots = List.of(ballot(3, a, b), blank);

        BordaResult result = BordaMethod.count(List.of(a, b), ballots, ClassicBorda.INSTANCE);

        assertThat(result.scores()).isEqualTo(Map.of(a, Fraction.of(3), b, Fraction.ZERO));
    }

    @Test
    void aWithdrawnCandidateIsPassedOverAndTheOnesAfterThemMoveUp() {
        // B is withdrawn: on the ballots below, C is first among the two standing, then second.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Ballot tiedWithWithdrawn = new Ballot(List.of(Set.of(a, b), Set.of(c)), BigInteger.ONE);
        List<Ballot> ballots = List.of(ballot(3, b, c), ballot(2, a, b, c), tiedWithWithdrawn);

        BordaResult result = BordaMethod.count(List.of(a, c), ballots, ClassicBorda.INSTANCE);

        assertThat(result.scores()).isEqualTo(Map.of(a, Fraction.of(3), c, Fraction.of(3)));
    }

    @Test
    void refusesACountWithNoCandidates() {
        assertThatThrownBy(() -> BordaMethod.count(List.of(), List.of(), ClassicBorda.INSTANCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("there must be at least one candidate");
    }

    private static Ballot ballot(int count, Candidate... ranking) {
        return new Ballot(List.of(ranking).stream().map(Set::of).toList(), BigInteger.valueOf(count));
    }
}
