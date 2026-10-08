package info.fkhodkov.rankedchoice.schulze;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/**
 * Wikipedia's "Schulze method" worked example: 45 voters, candidates A-E, whose d and p
 * matrices and final ranking (E&gt;A&gt;C&gt;B&gt;D) are given explicitly on that page --
 * verified by hand here (see the comment on {@code ballots} below) rather than assumed.
 */
class SchulzeMethodTest {

    @Test
    void reproducesTheWikipediaWorkedExample() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        Candidate e = Candidate.of("E");
        List<Candidate> candidates = List.of(a, b, c, d, e);

        // 5:ACBED 5:ADECB 8:BEDAC 3:CABED 7:CAEBD 2:CBADE 7:DCEBA 8:EBADC -- e.g. d(A,B): the
        // ballots ranking A ahead of B are ACBED(5)+ADECB(5)+CABED(3)+CAEBD(7)=20, matching the
        // page's own d(A,B)=20 exactly.
        List<Ballot> ballots = List.of(
                ballot(5, a, c, b, e, d),
                ballot(5, a, d, e, c, b),
                ballot(8, b, e, d, a, c),
                ballot(3, c, a, b, e, d),
                ballot(7, c, a, e, b, d),
                ballot(2, c, b, a, d, e),
                ballot(7, d, c, e, b, a),
                ballot(8, e, b, a, d, c));

        SchulzeResult result = SchulzeMethod.count(candidates, ballots);

        assertThat(result.pairwiseWins().get(a).get(b)).isEqualTo(BigInteger.valueOf(20));
        assertThat(result.pairwiseWins().get(b).get(a)).isEqualTo(BigInteger.valueOf(25));
        assertThat(result.pairwiseWins().get(a).get(c)).isEqualTo(BigInteger.valueOf(26));
        assertThat(result.pairwiseWins().get(c).get(a)).isEqualTo(BigInteger.valueOf(19));
        assertThat(result.pairwiseWins().get(a).get(d)).isEqualTo(BigInteger.valueOf(30));
        assertThat(result.pairwiseWins().get(d).get(a)).isEqualTo(BigInteger.valueOf(15));
        assertThat(result.pairwiseWins().get(a).get(e)).isEqualTo(BigInteger.valueOf(22));
        assertThat(result.pairwiseWins().get(e).get(a)).isEqualTo(BigInteger.valueOf(23));

        assertThat(result.strongestPaths().get(a).get(b)).isEqualTo(BigInteger.valueOf(28));
        assertThat(result.strongestPaths().get(a).get(c)).isEqualTo(BigInteger.valueOf(28));
        assertThat(result.strongestPaths().get(a).get(d)).isEqualTo(BigInteger.valueOf(30));
        assertThat(result.strongestPaths().get(a).get(e)).isEqualTo(BigInteger.valueOf(24));
        assertThat(result.strongestPaths().get(e).get(a)).isEqualTo(BigInteger.valueOf(25));
        assertThat(result.strongestPaths().get(b).get(d)).isEqualTo(BigInteger.valueOf(33));
        assertThat(result.strongestPaths().get(c).get(b)).isEqualTo(BigInteger.valueOf(29));

        assertThat(result.ranking()).containsExactly(Set.of(e), Set.of(a), Set.of(c), Set.of(b), Set.of(d));
        assertThat(result.winners()).containsExactly(e);
    }

    @Test
    void resolvesAGenuinePairwiseCycleViaTheStrongestPath() {
        // A rock-paper-scissors cycle in direct pairwise terms: A beats B (8-4), B beats C
        // (9-3), C beats A (7-5) -- no candidate wins every head-to-head matchup. Hand-derived
        // strongest paths (Floyd-Warshall by hand, k = A then B then C):
        //   p(C,B) updates to 7 via A: min(p(C,A)=7, p(A,B)=8) = 7 > 0
        //   p(A,C) updates to 8 via B: min(p(A,B)=8, p(B,C)=9) = 8 > 0
        //   p(B,A) updates to 7 via C: min(p(B,C)=9, p(C,A)=7) = 7 > 0
        // giving p(A,B)=8>p(B,A)=7, p(B,C)=9>p(C,B)=7, and p(A,C)=8>p(C,A)=7 -- so despite the
        // cycle in direct comparisons, the beatpath order resolves cleanly to A>B>C.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Ballot> ballots = List.of(
                ballot(5, a, b, c),
                ballot(4, b, c, a),
                ballot(3, c, a, b));

        SchulzeResult result = SchulzeMethod.count(List.of(a, b, c), ballots);

        assertThat(result.pairwiseWins().get(a).get(b)).isEqualTo(BigInteger.valueOf(8));
        assertThat(result.pairwiseWins().get(b).get(a)).isEqualTo(BigInteger.valueOf(4));
        assertThat(result.pairwiseWins().get(b).get(c)).isEqualTo(BigInteger.valueOf(9));
        assertThat(result.pairwiseWins().get(c).get(b)).isEqualTo(BigInteger.valueOf(3));
        assertThat(result.pairwiseWins().get(c).get(a)).isEqualTo(BigInteger.valueOf(7));
        assertThat(result.pairwiseWins().get(a).get(c)).isEqualTo(BigInteger.valueOf(5));

        assertThat(result.strongestPaths().get(a).get(b)).isEqualTo(BigInteger.valueOf(8));
        assertThat(result.strongestPaths().get(b).get(a)).isEqualTo(BigInteger.valueOf(7));
        assertThat(result.strongestPaths().get(b).get(c)).isEqualTo(BigInteger.valueOf(9));
        assertThat(result.strongestPaths().get(c).get(b)).isEqualTo(BigInteger.valueOf(7));
        assertThat(result.strongestPaths().get(a).get(c)).isEqualTo(BigInteger.valueOf(8));
        assertThat(result.strongestPaths().get(c).get(a)).isEqualTo(BigInteger.valueOf(7));

        assertThat(result.ranking()).containsExactly(Set.of(a), Set.of(b), Set.of(c));
        assertThat(result.winners()).containsExactly(a);
    }

    @Test
    void aPerfectDeadHeatBetweenTwoCandidatesIsAGenuineUnresolvedTie() {
        // One voter each way -- neither direction's strongest path can ever exceed the other,
        // since there's nothing for either to route around with only 2 candidates.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        List<Ballot> ballots = List.of(ballot(1, a, b), ballot(1, b, a));

        SchulzeResult result = SchulzeMethod.count(List.of(a, b), ballots);

        assertThat(result.strongestPaths().get(a).get(b)).isEqualTo(BigInteger.ZERO);
        assertThat(result.strongestPaths().get(b).get(a)).isEqualTo(BigInteger.ZERO);
        assertThat(result.ranking()).containsExactly(Set.of(a, b));
        assertThat(result.winners()).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void aTieGroupOnABallotContributesNoPreferenceBetweenItsOwnMembers() {
        // A and B tied for 1st, C 2nd: this ballot prefers both A and B over C, but neither A
        // nor B over the other.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Ballot ballot = new Ballot(List.of(Set.of(a, b), Set.of(c)), BigInteger.valueOf(10));

        SchulzeResult result = SchulzeMethod.count(List.of(a, b, c), List.of(ballot));

        assertThat(result.pairwiseWins().get(a).get(b)).isEqualTo(BigInteger.ZERO);
        assertThat(result.pairwiseWins().get(b).get(a)).isEqualTo(BigInteger.ZERO);
        assertThat(result.pairwiseWins().get(a).get(c)).isEqualTo(BigInteger.valueOf(10));
        assertThat(result.pairwiseWins().get(b).get(c)).isEqualTo(BigInteger.valueOf(10));
        assertThat(result.pairwiseWins().get(c).get(a)).isEqualTo(BigInteger.ZERO);
        assertThat(result.ranking()).containsExactly(Set.of(a, b), Set.of(c));
    }

    @Test
    void refusesACountWithNoCandidates() {
        assertThatThrownBy(() -> SchulzeMethod.count(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("there must be at least one candidate");
    }

    @Test
    void aCandidateLeftOffABallotEntirelyIsTreatedAsTiedForLastBehindEveryoneRanked() {
        // Ranking just A then B (C left off entirely) must produce the exact same pairwise
        // comparisons as explicitly ranking C last -- an unranked candidate is implicitly tied
        // for last with every other unranked candidate, behind everyone the ballot does rank.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Ballot leavesCOff = new Ballot(List.of(Set.of(a), Set.of(b)), BigInteger.valueOf(6));
        Ballot ranksCLast = new Ballot(List.of(Set.of(a), Set.of(b), Set.of(c)), BigInteger.valueOf(6));

        SchulzeResult withCOff = SchulzeMethod.count(List.of(a, b, c), List.of(leavesCOff));
        SchulzeResult withCLast = SchulzeMethod.count(List.of(a, b, c), List.of(ranksCLast));

        assertThat(withCOff.pairwiseWins()).isEqualTo(withCLast.pairwiseWins());
    }

    private static Ballot ballot(int count, Candidate... ranking) {
        return new Ballot(List.of(ranking).stream().map(Set::of).toList(), BigInteger.valueOf(count));
    }
}
