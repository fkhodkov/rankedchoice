package info.fkhodkov.rankedchoice.stv.meek;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.TiedRankingPolicy;
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
        // 1 seat, quota 5.000000001 (10 votes / 2, plus the smallest amount 9 decimal places
        // can hold, as droop's fixed-point quota always is). Nobody meets quota in round 1 (A=4,
        // B=4, C=2), so C (lowest) is eliminated; C's ballot ranks A next, so A's total becomes
        // 4+2=6 in round 2, clearing quota by 0.999999999 -- all whole numbers otherwise, so this
        // checks the elimination-transfer path without any keep-factor iteration at all (A is
        // never elected until the very round it wins outright). Every figure matches droop's
        // report (meek arithmetic=fixed defeat_batch=none) exactly.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(a)), BigInteger.valueOf(4)),
                new Ballot(List.of(Set.of(b)), BigInteger.valueOf(4)),
                new Ballot(List.of(Set.of(c), Set.of(a)), BigInteger.valueOf(2)));
        MeekRuleset ruleset = new MeekRuleset(Exact.INSTANCE, new DeclaredOrderTieBreak(List.of(a, b, c)));

        StvResult result = MeekCounter.count(List.of(a, b, c), ballots, 1, ruleset);

        assertThat(result.quota()).isEqualTo(Fraction.of(5_000_000_001L, 1_000_000_000));
        assertThat(result.electedInOrder()).containsExactly(a);
        assertThat(result.rounds()).hasSize(2);

        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.tallies()).isEqualTo(Map.of(a, Fraction.of(4), b, Fraction.of(4), c, Fraction.of(2)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Eliminated(c));
        assertThat(round1.exhaustedVotes()).isEqualTo(Fraction.ZERO);

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies()).isEqualTo(Map.of(a, Fraction.of(6), b, Fraction.of(4)));
        assertThat(round2.action()).isEqualTo(new RoundAction.Elected(a, Fraction.of(999_999_999, 1_000_000_000)));
        assertThat(round2.exhaustedVotes()).isEqualTo(Fraction.ZERO);
    }

    @Test
    void convergesAnElectedCandidatesKeepFactorAcrossSeveralRoundsAfterAnElimination() {
        // 2 seats, 300 votes, quota starts at 100.000000001 (300/3, plus droop's smallest
        // step). A wins round 1 outright (101 first preferences), keep factor converging toward
        // 100/101 -- in round 2 B holds 80.999999990, not 81, the rest of A's surplus lost to
        // rounding down at 9 decimal places. D (lowest, 29 votes) is eliminated in round 2,
        // transferring through A -- which needs a second bout of keep-factor convergence (in
        // exact arithmetic settling at 10/13) before B finally clears quota in round 3. In exact
        // rational arithmetic, with w_A=10/13, (A,B)=101 gives A 1010/13 and B 303/13, and
        // (D,A,C)=29 -- D excluded -- gives A 290/13 and C 87/13; summing, A = 1300/13 = 100
        // (quota), B = 80 + 303/13 = 1343/13, C = 90 + 87/13 = 1257/13. The counter's fixed-point
        // figures (B 103.307692230, C 96.692307670) agree with those to 6 decimal places, which
        // is what the assertions below truncate to, and match droop's own report exactly.
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
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(a, Fraction.of(999_999, 1_000_000)));

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies())
                .isEqualTo(Map.of(b, Fraction.of(80_999_999, 1_000_000), c, Fraction.of(90), d, Fraction.of(29)));
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
     * and A's 7 clears it by 0.499999999 (droop's quota is always a smallest step above the
     * exact figure). Every figure matches a copy of droop patched as described in {@link
     * MeekCounter}.
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
                new RoundAction.Eliminated(d), new RoundAction.Eliminated(c),
                new RoundAction.Elected(a, Fraction.of(499_999, 1_000_000)));
        assertThat(result.rounds().get(1).tallies())
                .isEqualTo(Map.of(a, Fraction.of(7), b, Fraction.of(5), c, Fraction.of(4)));
        assertThat(result.rounds().get(2).tallies()).isEqualTo(Map.of(a, Fraction.of(7), b, Fraction.of(6)));
        assertThat(result.quota()).isEqualTo(Fraction.of(13, 2));
    }

    /**
     * Warren's method differs from Meek's only once a ballot reaches a second elected candidate
     * with part of its weight already spent: Meek has that candidate keep its fraction of what's
     * left, Warren charges it a fixed amount per ballot (all that's left, if less).
     * <pre>
     *   40: A, B, C      30: B      20: C      10: D      (3 seats)
     * </pre>
     * <p>A and B are elected on first preferences under both. The A voters' ballots then pay A,
     * then B, then reach C; Warren's flat charge at B leaves less of them for C, so C finishes on
     * 25.287963160 under Warren against 27.673611080 under Meek, with correspondingly more value
     * left unassigned. Both counts match droop's {@code warren} and {@code meek} reports exactly.
     */
    @Test
    void warrenChargesAFlatAmountPerBallotWhereMeekKeepsAFraction() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        List<Candidate> candidates = List.of(a, b, c, d);
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(a), Set.of(b), Set.of(c)), BigInteger.valueOf(40)),
                new Ballot(List.of(Set.of(b)), BigInteger.valueOf(30)),
                new Ballot(List.of(Set.of(c)), BigInteger.valueOf(20)),
                new Ballot(List.of(Set.of(d)), BigInteger.valueOf(10)));

        StvResult meek = MeekCounter.count(candidates, ballots, 3, ruleset(candidates, KeepRule.MEEK));
        StvResult warren = MeekCounter.count(candidates, ballots, 3, ruleset(candidates, KeepRule.WARREN));

        assertThat(meek.electedInOrder()).containsExactly(a, b, c);
        assertThat(warren.electedInOrder()).containsExactly(a, b, c);
        assertThat(meek.rounds().get(2).tallies())
                .isEqualTo(Map.of(c, Fraction.of(27_673_611_080L, 1_000_000_000), d, Fraction.of(10)));
        assertThat(warren.rounds().get(2).tallies())
                .isEqualTo(Map.of(c, Fraction.of(25_287_963_160L, 1_000_000_000), d, Fraction.of(10)));
        assertThat(meek.rounds().get(2).exhaustedVotes()).isEqualTo(Fraction.of(14_166_666_670L, 1_000_000_000));
        assertThat(warren.rounds().get(2).exhaustedVotes()).isEqualTo(Fraction.of(19_874_175_510L, 1_000_000_000));
        assertThat(warren.quota()).isEqualTo(Fraction.of(20_031_456_123L, 1_000_000_000));
    }

    /**
     * A count past the fixed-point limit is refused with the limit, even where one ballot's count,
     * or the sum of two, is past a {@code long}.
     */
    @Test
    void refusesTooManyVotesBeforeTakingAnyCountAsALong() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        List<Candidate> candidates = List.of(a, b);
        BigInteger pastLong = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        BigInteger half = BigInteger.valueOf(Long.MAX_VALUE / 2 + 1);
        List<List<Ballot>> elections = List.of(
                List.of(new Ballot(List.of(Set.of(a)), pastLong)),
                List.of(new Ballot(List.of(Set.of(a)), half), new Ballot(List.of(Set.of(a)), half)));
        for (KeepRule keepRule : KeepRule.values()) {
            for (List<Ballot> ballots : elections) {
                assertThatThrownBy(() -> MeekCounter.count(candidates, ballots, 1, ruleset(candidates, keepRule)))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(pastLong + " votes is too many to count at 9 decimal places")
                        .hasMessageContaining("no precision leaves room for them");
            }
        }
    }

    /**
     * Election 265 of tools/reference/droop's random elections, at 6 places: before an exclusion
     * the elected candidates' votes less the quota sum below zero, rounding having left one a hair
     * under it. "Within the surplus of the lowest" then took in no hopeful at all, not even the
     * lowest, and the count stopped on an empty tie, as droop's does. Nothing is left to transfer,
     * so the lowest goes, as at every other precision, which elect the same four.
     */
    @Test
    void aSurplusRoundedBelowZeroStillExcludesTheLowestHopeful() {
        ParsedElection election = BltParser.parse("""
            9 4
            1069 2 0
            1897 6 4 2 1 7 8 5 0
            980 6 8 1 0
            1556 9 7 0
            281 2 6 8 9 7 1 3 0
            22 3 6 8 5 0
            1746 1 6 8 2 4 3 5 7 9 0
            553 2 7 5 0
            1539 2 6 9 3 1 8 0
            789 2 5 0
            839 8 0
            136 9 2 6 0
            861 3 6 0
            1478 4 6 8 5 1 3 2 7 9 0
            1233 2 4 9 6 1 7 5 3 0
            1308 9 8 2 6 4 1 5 0
            1451 2 9 5 0
            234 3 0
            1634 2 0
            242 5 0
            422 7 2 6 8 0
            1699 1 0
            807 1 7 8 0
            1020 6 0
            1618 2 6 1 9 5 4 3 0
            453 3 2 6 0
            1593 1 0
            1002 9 6 5 2 0
            1914 2 5 6 4 8 9 0
            681 3 0
            1456 3 6 7 4 9 2 0
            1580 9 6 0
            1847 6 1 2 4 0
            0
            "C1"
            "C2"
            "C3"
            "C4"
            "C5"
            "C6"
            "C7"
            "C8"
            "C9"
            "random 265"
            """.lines());
        MeekRuleset ruleset = new MeekRuleset(Exact.INSTANCE, new DeclaredOrderTieBreak(election.candidates()),
                TiedRankingPolicy.SPLIT, 0, 6, KeepRule.MEEK);

        StvResult result = MeekCounter.count(election.candidates(), election.ballots(), election.seats(), ruleset);

        assertThat(result.electedInOrder()).extracting(Candidate::name).containsExactly("C2", "C6", "C1", "C9");
    }

    private static MeekRuleset ruleset(List<Candidate> candidates, KeepRule keepRule) {
        return new MeekRuleset(Exact.INSTANCE, new DeclaredOrderTieBreak(candidates), TiedRankingPolicy.SPLIT, 0,
                MeekRuleset.DEFAULT_PRECISION, keepRule);
    }
}
