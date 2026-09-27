package info.fkhodkov.rankedchoice.stv.meek;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;

class MeekCounterTest {

    @Test
    void electsBothCandidatesImmediatelyWhenHopefulsAlreadyMatchRemainingSeats() {
        // Only 2 candidates stand for 2 seats -- both are elected in round 1 without ever
        // checking quota, same shortcut StvCounter takes; surplus is reported as ZERO by
        // convention, same as that shortcut.
        Candidate p = Candidate.of("P");
        Candidate q = Candidate.of("Q");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(p)), BigInteger.valueOf(5)),
                new Ballot(List.of(Set.of(q)), BigInteger.valueOf(3)));
        MeekRuleset ruleset = new MeekRuleset(Exact.INSTANCE, new DeclaredOrderTieBreak(List.of(p, q)));

        StvResult result = MeekCounter.count(List.of(p, q), ballots, 2, ruleset);

        assertThat(result.electedInOrder()).containsExactly(p, q);
        assertThat(result.rounds()).hasSize(2);
        assertThat(result.rounds().get(0).action()).isEqualTo(new RoundAction.Elected(p, Fraction.ZERO));
        assertThat(result.rounds().get(1).action()).isEqualTo(new RoundAction.Elected(q, Fraction.ZERO));
    }

    @Test
    void eliminatesThenElectsWithoutNeedingAnyKeepFactorConvergence() {
        // 1 seat, quota 5 (10 votes / 2). Nobody meets quota in round 1 (A=4, B=4, C=2), so C
        // (lowest) is eliminated; C's ballot ranks A next, so A's total becomes 4+2=6 in round
        // 2, clearing quota by 1 -- all whole numbers, no repeating decimals, so this checks
        // the elimination-transfer path without exercising rule 2.9's convergence loop at all
        // (A is never elected until the very round it wins outright).
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(a)), BigInteger.valueOf(4)),
                new Ballot(List.of(Set.of(b)), BigInteger.valueOf(4)),
                new Ballot(List.of(Set.of(c), Set.of(a)), BigInteger.valueOf(2)));
        MeekRuleset ruleset = new MeekRuleset(Exact.INSTANCE, new DeclaredOrderTieBreak(List.of(a, b, c)));

        StvResult result = MeekCounter.count(List.of(a, b, c), ballots, 1, ruleset);

        assertThat(result.quota()).isEqualTo(Fraction.of(5));
        assertThat(result.electedInOrder()).containsExactly(a);
        assertThat(result.rounds()).hasSize(2);

        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.tallies()).isEqualTo(Map.of(a, Fraction.of(4), b, Fraction.of(4), c, Fraction.of(2)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Eliminated(c));
        assertThat(round1.exhaustedVotes()).isEqualTo(Fraction.ZERO);

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies()).isEqualTo(Map.of(a, Fraction.of(6), b, Fraction.of(4)));
        assertThat(round2.action()).isEqualTo(new RoundAction.Elected(a, Fraction.of(1)));
        assertThat(round2.exhaustedVotes()).isEqualTo(Fraction.ZERO);
    }

    @Test
    void convergesAnElectedCandidatesKeepFactorAcrossSeveralRoundsAfterAnElimination() {
        // 2 seats, 300 votes, quota starts at 100 (300/3). A wins round 1 outright (101 first
        // preferences), keep factor converging toward 100/101. D (lowest, 29 votes) is
        // eliminated in round 2, transferring through A -- which needs a second bout of
        // keep-factor convergence (this time settling at exactly 10/13) before B finally
        // clears quota in round 3. Hand-derived by simulating rules 2.2-2.9 directly (not
        // taken from an external source): with w_A=10/13, (A,B)=101 gives A 1010/13 and B
        // 303/13, and (D,A,C)=29 -- D excluded -- gives A 290/13 and C 87/13; summing,
        // A = (1010+290)/13 = 1300/13 = 100 exactly (quota, converged), B = 80 + 303/13 =
        // 1343/13, C = 90 + 87/13 = 1257/13. All of this is exact rational arithmetic; the
        // assertions below truncate to 6dp (matching how any real Meek report would show it)
        // since the counter itself only carries double precision through its convergence loop.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(a), Set.of(b)), BigInteger.valueOf(101)),
                new Ballot(List.of(Set.of(b)), BigInteger.valueOf(80)),
                new Ballot(List.of(Set.of(c)), BigInteger.valueOf(90)),
                new Ballot(List.of(Set.of(d), Set.of(a), Set.of(c)), BigInteger.valueOf(29)));
        MeekRuleset ruleset = new MeekRuleset(new TruncateDecimal(6), new DeclaredOrderTieBreak(List.of(a, b, c, d)));

        StvResult result = MeekCounter.count(List.of(a, b, c, d), ballots, 2, ruleset);

        assertThat(result.quota()).isEqualTo(Fraction.of(100));
        assertThat(result.electedInOrder()).containsExactly(a, b);
        assertThat(result.rounds()).hasSize(3);

        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.tallies()).isEqualTo(Map.of(
                a, Fraction.of(101), b, Fraction.of(80), c, Fraction.of(90), d, Fraction.of(29)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(a, Fraction.of(1)));

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies()).isEqualTo(Map.of(b, Fraction.of(81), c, Fraction.of(90), d, Fraction.of(29)));
        assertThat(round2.action()).isEqualTo(new RoundAction.Eliminated(d));

        RoundRecord round3 = result.rounds().get(2);
        assertThat(round3.tallies())
                .containsEntry(b, Fraction.of(103307692, 1_000_000))
                .containsEntry(c, Fraction.of(96692307, 1_000_000));
        assertThat(round3.action()).isEqualTo(new RoundAction.Elected(b, Fraction.of(3307692, 1_000_000)));
    }

    /**
     * Tied rankings under the default {@code split} policy, including the one place this
     * deliberately differs from droop's Meek rule (see {@link MeekCounter}): a ballot whose
     * untied first choice is excluded must carry on to its tied later rank, not stop dead.
     * <pre>
     *   7: A      4: B      3: C      2: D, B=C      (1 seat)
     * </pre>
     * <p>Round 1 -- A=7, B=4, C=3, D=2; quota (16-0)/2 = 8, nobody reaches it; D is excluded.
     * <p>Round 2 -- D's 2 ballots move on to their tied rank and split evenly: B=4+1=5,
     * C=3+1=4 (droop would lose those 2 votes here instead). Nobody reaches 8; C is excluded,
     * its own 3 single-preference ballots exhaust.
     * <p>Round 3 -- the tied rank now has only B standing, so B inherits the whole of those 2
     * ballots, not just the half it held while the tie lasted: B=4+2=6. Quota (16-3)/2 = 6.5,
     * and A's 7 clears it.
     */
    @Test
    void splitTiedRankingsCarryOnPastAnExcludedFirstChoiceAndReSplitAmongSurvivors() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(a)), BigInteger.valueOf(7)),
                new Ballot(List.of(Set.of(b)), BigInteger.valueOf(4)),
                new Ballot(List.of(Set.of(c)), BigInteger.valueOf(3)),
                new Ballot(List.of(Set.of(d), Set.of(b, c)), BigInteger.valueOf(2)));
        MeekRuleset ruleset = new MeekRuleset(new TruncateDecimal(6), new DeclaredOrderTieBreak(List.of(a, b, c, d)));

        StvResult result = MeekCounter.count(List.of(a, b, c, d), ballots, 1, ruleset);

        assertThat(result.rounds()).extracting(RoundRecord::action).containsExactly(
                new RoundAction.Eliminated(d), new RoundAction.Eliminated(c), new RoundAction.Elected(a, Fraction.of(1, 2)));
        assertThat(result.rounds().get(1).tallies())
                .isEqualTo(Map.of(a, Fraction.of(7), b, Fraction.of(5), c, Fraction.of(4)));
        assertThat(result.rounds().get(2).tallies()).isEqualTo(Map.of(a, Fraction.of(7), b, Fraction.of(6)));
        assertThat(result.quota()).isEqualTo(Fraction.of(13, 2));
    }
}
