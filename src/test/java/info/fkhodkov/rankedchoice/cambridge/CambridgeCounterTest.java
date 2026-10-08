package info.fkhodkov.rankedchoice.cambridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.TiedRankingPolicy;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;

class CambridgeCounterTest {

    private static final Candidate A = Candidate.of("A");
    private static final Candidate B = Candidate.of("B");
    private static final Candidate C = Candidate.of("C");
    private static final Candidate D = Candidate.of("D");
    private static final Candidate E = Candidate.of("E");
    private static final List<Candidate> CANDIDATES = List.of(A, B, C, D, E);

    @Test
    void cincinnatiOrderTakesEveryNthBallotStartingFromTheNth() {
        // 10 ballots, surplus 3: n = round(3.33) = 3 -- the 3rd, 6th, 9th, then 4th, 7th, 10th, ...
        assertThat(CambridgeCounter.transferOrder(10, 3)).containsExactly(2, 5, 8, 3, 6, 9, 4, 7, 0, 1);
        // n = 7 / 2 = 3.5 rounds half up to 4.
        assertThat(CambridgeCounter.transferOrder(7, 2)).containsExactly(3, 4, 5, 6, 0, 1, 2);
        // A surplus of exactly 1 takes the last ballot first.
        assertThat(CambridgeCounter.transferOrder(5, 1)).containsExactly(4, 0, 1, 2, 3);
    }

    /**
     * Hand-worked (and matched by OpenSTV's CambridgeSTV): 300 ballots, 2 seats, quota 300/3 + 1 =
     * 101. A's 130 ballots alternate A,B / A,C in the order cast, starting with A,B.
     * <pre>
     *   130: A then B or C, alternating   60: B   70: C   25: D, C   15: E, B
     * </pre>
     * <p>A's surplus of 29: n = round(130 / 29) = 4, so A's 4th, 8th, 12th... ballots move -- all
     * of them A,C ballots -- and C gets all 29 (fractional STV would have split them evenly).
     * Nobody else has a surplus; D (25) and E (15) are both under 50, so the first exclusion takes
     * both, E first. E's 15 go to B (75); D's ballots go to C, who closes on reaching 101 after 2,
     * so D's other 23 have nowhere to go. C is elected, filling the second seat.
     */
    @Test
    void transfersWholeBallotsByTheCincinnatiMethod() {
        List<Ballot> ballots = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            ballots.add(ballot(1, A, i % 2 == 0 ? B : C));
        }
        ballots.add(ballot(60, B));
        ballots.add(ballot(70, C));
        ballots.add(ballot(25, D, C));
        ballots.add(ballot(15, E, B));

        StvResult result = CambridgeCounter.count(CANDIDATES, ballots, 2, ruleset());

        assertThat(result.quota()).isEqualTo(Fraction.of(101));
        assertThat(result.electedInOrder()).containsExactly(A, C);
        assertThat(result.rounds()).extracting(round -> round.action()).containsExactly(
                new RoundAction.Elected(A, Fraction.of(29)),
                new RoundAction.Eliminated(E),
                new RoundAction.Eliminated(D),
                new RoundAction.Elected(C, Fraction.ZERO));
        assertThat(result.rounds().get(1).tallies()).isEqualTo(Map.of(
                B, Fraction.of(60), C, Fraction.of(99), D, Fraction.of(25), E, Fraction.of(15)));
        assertThat(result.rounds().get(3).tallies()).isEqualTo(Map.of(B, Fraction.of(75), C, Fraction.of(101)));
        assertThat(result.rounds().get(3).exhaustedVotes()).isEqualTo(Fraction.of(23));
    }

    @Test
    void refusesTooFewBallotsAndSplitTies() {
        assertThatThrownBy(() -> CambridgeCounter.count(CANDIDATES, List.of(ballot(99, A)), 2, ruleset()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 50 ballots per seat (100 here) but there are only 99");
        CambridgeRuleset splitting =
                new CambridgeRuleset(new DeclaredOrderTieBreak(CANDIDATES), TiedRankingPolicy.SPLIT, 0);
        assertThatThrownBy(() -> CambridgeCounter.count(CANDIDATES, List.of(ballot(200, A)), 2, splitting))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Cambridge STV has no defined rule for splitting");
    }

    /**
     * Ballots are counted one by one, so a count past the limit is refused with the limit, even
     * where one ballot's count, or the sum of two, is past a {@code long}.
     */
    @Test
    void refusesTooManyBallotsBeforeTakingAnyCountAsALong() {
        BigInteger pastLong = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        BigInteger half = BigInteger.valueOf(Long.MAX_VALUE / 2 + 1);
        List<List<Ballot>> elections = List.of(
                List.of(new Ballot(List.of(Set.of(A)), BigInteger.valueOf(Integer.MAX_VALUE / 2 + 1))),
                List.of(new Ballot(List.of(Set.of(A)), pastLong)),
                List.of(new Ballot(List.of(Set.of(A)), half), new Ballot(List.of(Set.of(B)), half)));
        List<String> totals = List.of(String.valueOf(Integer.MAX_VALUE / 2 + 1), pastLong.toString(),
                pastLong.toString());
        for (int i = 0; i < elections.size(); i++) {
            List<Ballot> ballots = elections.get(i);
            assertThatThrownBy(() -> CambridgeCounter.count(CANDIDATES, ballots, 2, ruleset()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(totals.get(i) + " ballots is too many to count one by one (at most 1073741823)");
        }
    }

    private static CambridgeRuleset ruleset() {
        return new CambridgeRuleset(new DeclaredOrderTieBreak(CANDIDATES));
    }

    private static Ballot ballot(int count, Candidate... ranking) {
        List<Set<Candidate>> ranks = new ArrayList<>();
        for (Candidate candidate : ranking) {
            ranks.add(Set.of(candidate));
        }
        return new Ballot(ranks, BigInteger.valueOf(count));
    }
}
