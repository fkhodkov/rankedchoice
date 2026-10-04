package info.fkhodkov.rankedchoice.stv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.config.ConfigLoader;
import info.fkhodkov.rankedchoice.core.config.ElectionConfig;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.config.StvRulesetParser;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.quota.HareQuota;
import info.fkhodkov.rankedchoice.stv.quota.ImperialiQuota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
import info.fkhodkov.rankedchoice.stv.tiebreak.PreviousRoundVotesTieBreak;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.LastParcelDeferredRoundingTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.LastParcelTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.WigmTransfer;

class StvCounterTest {

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

    private static StvRuleset ruleset(Candidate... declaredOrder) {
        return new StvRuleset(
                DroopQuota.INSTANCE,
                FractionalTransfer.INSTANCE,
                Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(declaredOrder)));
    }

    /**
     * Hand-computed 4-candidate, 2-seat count (60 ballots), structured after the classic
     * "surplus transfer" teaching example but with figures fully derived and verified here,
     * not taken from any external source. Ballot groups:
     * <pre>
     *   22: A,B,C     11: A,C      10: B,C      9: C      6: D,C      2: D
     * </pre>
     * First preferences: A=33, B=10, C=9, D=8 (total 60). Droop quota = floor(60/3)+1 = 21.
     *
     * <p>Round 1 — A meets quota, surplus 33-21=12, transfer value 12/33 = 4/11:
     *   B gains 22*(4/11)=8 -> 18; C gains 11*(4/11)=4 -> 13; A settles at 21 (elected).
     * <p>Round 2 — none meet quota (B=18,C=13,D=8); D (lowest) eliminated: 6 ballots -> C
     *   (13+6=19), 2 ballots exhaust (no further preference). B stays 18.
     * <p>Round 3 — none meet quota (B=18,C=19); B (lowest) eliminated: the 22 ballots held at
     *   value 4/11 (originally A's, ranked A,B,C) and the 10 original B,C ballots all move to
     *   C: C gains 22*(4/11) + 10*1 = 8+10=18 -> 37.
     * <p>Round 4 — only C remains continuing for the 1 remaining seat: elected without a
     *   further transfer (the "remaining continuing == remaining seats" rule).
     * <p>Final result: A and C elected; 2 votes exhausted throughout.
     */
    @Test
    void handComputedFourCandidateTwoSeatCount() {
        List<Ballot> ballots = List.of(
                ballot(22, A, B, C),
                ballot(11, A, C),
                ballot(10, B, C),
                ballot(9, C),
                ballot(6, D, C),
                ballot(2, D));

        StvResult result = StvCounter.count(List.of(A, B, C, D), ballots, 2, ruleset(A, B, C, D));

        assertThat(result.quota()).isEqualTo(Fraction.of(21));
        assertThat(result.electedInOrder()).containsExactly(A, C);
        assertThat(result.rounds()).hasSize(4);

        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.tallies()).isEqualTo(Map.of(A, Fraction.of(33), B, Fraction.of(10), C, Fraction.of(9), D, Fraction.of(8)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(A, Fraction.of(12)));
        assertThat(round1.exhaustedVotes()).isEqualTo(Fraction.ZERO);

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies()).isEqualTo(Map.of(B, Fraction.of(18), C, Fraction.of(13), D, Fraction.of(8)));
        assertThat(round2.action()).isEqualTo(new RoundAction.Eliminated(D));
        assertThat(round2.exhaustedVotes()).isEqualTo(Fraction.ZERO);

        RoundRecord round3 = result.rounds().get(2);
        assertThat(round3.tallies()).isEqualTo(Map.of(B, Fraction.of(18), C, Fraction.of(19)));
        assertThat(round3.action()).isEqualTo(new RoundAction.Eliminated(B));
        assertThat(round3.exhaustedVotes()).isEqualTo(Fraction.of(2));

        RoundRecord round4 = result.rounds().get(3);
        assertThat(round4.tallies()).isEqualTo(Map.of(C, Fraction.of(37)));
        assertThat(round4.action()).isEqualTo(new RoundAction.Elected(C, Fraction.ZERO));
        assertThat(round4.exhaustedVotes()).isEqualTo(Fraction.of(2));
    }

    @Test
    void shortcutElectsRemainingContinuingCandidatesWithoutTransfer() {
        List<Ballot> ballots = List.of(ballot(5, A), ballot(3, B));

        StvResult result = StvCounter.count(List.of(A, B), ballots, 2, ruleset(A, B));

        assertThat(result.rounds()).hasSize(2);
        assertThat(result.electedInOrder()).containsExactly(A, B);
        assertThat(result.rounds()).allSatisfy(round ->
                assertThat(round.action()).isInstanceOfSatisfying(RoundAction.Elected.class,
                        elected -> assertThat(elected.surplusTransferred()).isEqualTo(Fraction.ZERO)));
    }

    /**
     * Hand-derived 3-candidate/2-seat count (80 ballots) covering the case where two
     * candidates meet quota in the very same tally snapshot:
     * <pre>
     *   40: A,B      30: B      10: C
     * </pre>
     * Total 80, quota = floor(80/3)+1 = 27. Both A (40) and B (30) meet quota in round 1's
     * first count.
     *
     * <p>Both must be declared elected as a single batch <em>before</em> either surplus is
     * transferred — otherwise A's surplus, transferred while B is still (wrongly) treated as
     * continuing, would flow to B along its ballots' A,B ranking and inflate B's tally past
     * its true, frozen first-preference count of 30. Surpluses are then applied one at a
     * time, largest first (A's 13, then B's 3), each becoming its own round; since B is no
     * longer continuing by the time A's surplus moves, A's ballots (ranked A,B, no further
     * preference) exhaust instead of reaching B.
     *
     * <p>Round 1 — tallies {A:40, B:30, C:10}; A and B both meet quota, filling both seats, so
     *   the count ends there: neither surplus is transferred.
     * <p>Round 2 — B's election recorded on its untouched tally of 30. C never sees a transfer.
     */
    @Test
    void candidatesMeetingQuotaSimultaneouslyAreElectedAsABatchBeforeAnyTransfer() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Ballot> ballots = List.of(ballot(40, a, b), ballot(30, b), ballot(10, c));

        StvResult result = StvCounter.count(List.of(a, b, c), ballots, 2, ruleset(a, b, c));

        assertThat(result.quota()).isEqualTo(Fraction.of(27));
        assertThat(result.electedInOrder()).containsExactly(a, b);
        assertThat(result.rounds()).hasSize(2);

        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.roundNumber()).isEqualTo(1);
        assertThat(round1.tallies()).isEqualTo(Map.of(a, Fraction.of(40), b, Fraction.of(30), c, Fraction.of(10)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(a, Fraction.ZERO));
        assertThat(round1.exhaustedVotes()).isEqualTo(Fraction.ZERO);

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.roundNumber()).isEqualTo(2);
        // B's own tally must still read 30 here — not inflated by A's just-applied surplus.
        assertThat(round2.tallies()).isEqualTo(Map.of(b, Fraction.of(30), c, Fraction.of(10)));
        assertThat(round2.action()).isEqualTo(new RoundAction.Elected(b, Fraction.ZERO));
        assertThat(round2.exhaustedVotes()).isEqualTo(Fraction.ZERO);
    }

    /**
     * Demonstrates {@link LastParcelDeferredRoundingTransfer}'s defining behavior against
     * {@link LastParcelTransfer}: the shared transfer ratio stays exact, and rounding is
     * applied only once each destination candidate's own gain is computed — the ACT
     * Legislative Assembly's Hare-Clark convention, confirmed against real election data.
     *
     * <p>4 candidates (A, X, Y — Z absorbs the rest of the total purely to fix the quota),
     * 2 seats, 257 ballots:
     * <pre>
     *   77: A,X     22: A,Y     79: X     79: Z (259... see below)
     * </pre>
     * Actually: {@code 79: X} and {@code 79: Y} are each candidates' own first preferences
     * (not transfers), so nobody but A needs a next-preference target. Total 257, Droop quota
     * = floor(257/3)+1 = 86. A's total (77+22=99) gives a surplus of 13, split into 77 papers
     * to X and 22 to Y (all 99 specify a next preference, so CP=99).
     *
     * <p>Exact ratio = 13/99. Truncated to 2dp <em>first</em> (as {@link LastParcelTransfer}
     * would): 0.13, giving X = 77*0.13 = 10.01 and Y = 22*0.13 = 2.86. Truncated only after
     * multiplying by each destination's own count (as this class does): X = truncate(77*13/99,
     * 2) = truncate(1001/99, 2) = 10.11, and Y = truncate(22*13/99, 2) = truncate(286/99, 2) =
     * 2.88 — genuinely different results, not just a rounding-mode footnote.
     */
    @Test
    void deferredRoundingLastParcelTransferRoundsEachDestinationsGainNotTheSharedRatio() {
        Candidate a = Candidate.of("A");
        Candidate x = Candidate.of("X");
        Candidate y = Candidate.of("Y");
        List<Ballot> ballots = List.of(ballot(77, a, x), ballot(22, a, y), ballot(79, x), ballot(79, y));
        StvRuleset ruleset = new StvRuleset(
                DroopQuota.INSTANCE, LastParcelDeferredRoundingTransfer.INSTANCE, new TruncateDecimal(2),
                new DeclaredOrderTieBreak(List.of(a, x, y)));

        StvResult result = StvCounter.count(List.of(a, x, y), ballots, 2, ruleset);

        assertThat(result.quota()).isEqualTo(Fraction.of(86));
        RoundRecord round1 = result.rounds().getFirst();
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(a, Fraction.of(13)));

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies())
                .containsEntry(x, Fraction.of(8911, 100))
                .containsEntry(y, Fraction.of(2047, 25));
    }

    @Test
    void tieBreakResolvesEliminationOrderDeterministically() {
        // X has a clear lead; Y and Z are tied for last on 3 votes each, with the declared
        // order [X, Y, Z] meaning Y is eliminated first when tied with Z.
        List<Ballot> ballots = List.of(
                ballot(5, Candidate.of("X")),
                ballot(3, Candidate.of("Y"), Candidate.of("X")),
                ballot(3, Candidate.of("Z"), Candidate.of("X")));
        Candidate x = Candidate.of("X");
        Candidate y = Candidate.of("Y");
        Candidate z = Candidate.of("Z");

        StvResult result = StvCounter.count(List.of(x, y, z), ballots, 1, ruleset(x, y, z));

        assertThat(result.electedInOrder()).containsExactly(x);
        assertThat(result.rounds()).hasSize(2);
        assertThat(result.rounds().get(0).action()).isEqualTo(new RoundAction.Eliminated(y));
        // X fills the only seat, so the count ends: X's surplus isn't transferred.
        assertThat(result.rounds().get(1).action()).isEqualTo(new RoundAction.Elected(x, Fraction.ZERO));
    }

    @Test
    void rejectsTiedRankingsUnderNonFractionalTransferMethods() {
        Ballot tiedBallot = new Ballot(List.of(Set.of(A, B)), BigInteger.ONE);
        StvRuleset wigmRuleset = new StvRuleset(
                DroopQuota.INSTANCE, WigmTransfer.INSTANCE, Exact.INSTANCE, new DeclaredOrderTieBreak(List.of(A, B)));
        assertThatThrownBy(() -> StvCounter.count(List.of(A, B), List.of(tiedBallot), 1, wigmRuleset))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMoreSeatsThanCandidates() {
        assertThatThrownBy(() -> StvCounter.count(List.of(A), List.of(ballot(1, A)), 2, ruleset(A)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLastParcelSurplusLargerThanItsTransferablePapersMovesThemAtFullValueAndExhaustsTheRest() {
        // Quota 6 (Droop, 16 votes, 2 seats). A's surplus is 6, but only the 2 A>B papers can
        // move: they go at their full value, 1 each, not 6/2 = 3 (a paper never gains value),
        // and the other 4 votes of the surplus are exhausted. 6 + 3 + 3 + 4 = 16.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(a)), BigInteger.valueOf(10)),
                new Ballot(List.of(Set.of(a), Set.of(b)), BigInteger.valueOf(2)),
                new Ballot(List.of(Set.of(c)), BigInteger.valueOf(3)),
                new Ballot(List.of(Set.of(b)), BigInteger.ONE));
        StvRuleset ruleset = new StvRuleset(DroopQuota.INSTANCE, LastParcelTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(a, b, c)));

        StvResult result = StvCounter.count(List.of(a, b, c), ballots, 2, ruleset);

        RoundRecord afterSurplus = result.rounds().get(1);
        assertThat(afterSurplus.tallies()).containsEntry(b, Fraction.of(3)).containsEntry(c, Fraction.of(3));
        assertThat(afterSurplus.exhaustedVotes()).isEqualTo(Fraction.of(4));
        assertThat(afterSurplus.roundingLoss()).isEqualTo(Fraction.ZERO);
    }

    @Test
    void roundingLossIsWhatATruncatedTransferValueFailsToPassOn() {
        // A's surplus of 6 moves at a truncated 6/11 -> 0.54: B gets 5.94, and 0.06 is lost.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(a), Set.of(b)), BigInteger.valueOf(11)),
                new Ballot(List.of(Set.of(c)), BigInteger.valueOf(2)));
        StvRuleset ruleset = new StvRuleset(DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, new TruncateDecimal(2),
                new DeclaredOrderTieBreak(List.of(a, b, c)));

        StvResult result = StvCounter.count(List.of(a, b, c), ballots, 2, ruleset);

        assertThat(result.rounds().getFirst().roundingLoss()).isEqualTo(Fraction.ZERO);
        assertThat(result.rounds().get(1).roundingLoss()).isEqualTo(Fraction.of(6, 100));
    }

    @Test
    void roundingRuleIsAppliedToTheTransferValue() {
        // 13 voters, 2 seats -> quota = floor(13/3)+1 = 5. A's 11 ballots give a surplus of 6,
        // an exact transfer value of 6/11 = 0.545454... Truncating to 2 places (0.54) means B
        // receives 11 * 27/50 = 297/50 (5.94), not the clean 6 that Exact rounding would give
        // — a small, deliberate loss to rounding, same as real truncating rulesets.
        List<Ballot> ballots = List.of(ballot(11, A, B), ballot(2, C));
        StvRuleset ruleset = new StvRuleset(
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, new TruncateDecimal(2),
                new DeclaredOrderTieBreak(List.of(A, B, C)));

        StvResult result = StvCounter.count(List.of(A, B, C), ballots, 2, ruleset);

        assertThat(result.electedInOrder()).containsExactly(A, B);
        assertThat(result.rounds().get(1).tallies()).containsEntry(B, Fraction.of(297, 50));
    }

    /**
     * Demonstrates that WigmTransfer and FractionalTransfer genuinely diverge once a
     * candidate holds ballots that arrived at more than one distinct value — which only
     * happens after they've received a transfer themselves. 5 candidates, 3 seats:
     * <pre>
     *   10: P,X   13: P   7: Q,X   3: X,Y   8: Y   9: Z
     * </pre>
     * Total 50, quota = floor(50/4)+1 = 13. Both methods agree through rounds 1-3:
     * <p>Round 1 — P (23) meets quota, surplus 10, ratio truncate(10/23, 2) = 0.43 (both
     *   methods agree: every one of P's ballots is at arrival value 1). X gains 10*0.43=4.3
     *   (-> 7.3 with its own 3); the other 13 P ballots (no 2nd preference) exhaust at 0.43
     *   each (5.59 exhausted).
     * <p>Round 2 — Q (7, lowest) eliminated in full to X (7.3+7=14.3).
     * <p>Round 3 — X (14.3) meets quota, surplus 1.3. X now holds two arrival-value groups:
     *   10 ballots at 0.43 (from P, no further preference) and 10 at 1 (7 from Q + 3 of its
     *   own, ranked X,Y). The exact ratio surplus/candidateVotes = 13/143 = 1/11, which
     *   truncates to 0.09 for <em>either</em> method on the value-1 group. But for the
     *   value-0.43 group: FractionalTransfer computes 0.43 * 0.09 = 0.0387, while WigmTransfer
     *   computes truncate(0.43 * 13/143, 2) = truncate(43/1100, 2) = 0.03 — a different
     *   number, because rounding doesn't commute with multiplication. That group exhausts
     *   (no 3rd preference), so the two methods' total exhausted-votes after round 3 differ:
     *   5.59 + (10*0.0387 + 7*0.09) = 6.607 for FractionalTransfer, vs.
     *   5.59 + (10*0.03 + 7*0.09) = 6.52 for WigmTransfer. Y's own tally is unaffected (it
     *   only receives the value-1 group, where the methods always agree).
     */
    @Test
    void wigmAndFractionalTransferDivergeOnceACandidateHoldsMixedArrivalValues() {
        Candidate p = Candidate.of("P");
        Candidate q = Candidate.of("Q");
        Candidate x = Candidate.of("X");
        Candidate y = Candidate.of("Y");
        Candidate z = Candidate.of("Z");
        List<Ballot> ballots = List.of(
                ballot(10, p, x),
                ballot(13, p),
                ballot(7, q, x),
                ballot(3, x, y),
                ballot(8, y),
                ballot(9, z));
        List<Candidate> roster = List.of(p, q, x, y, z);
        TruncateDecimal rounding = new TruncateDecimal(2);
        DeclaredOrderTieBreak tieBreak = new DeclaredOrderTieBreak(roster);

        StvResult fractional = StvCounter.count(
                roster, ballots, 3, new StvRuleset(DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, rounding, tieBreak));
        StvResult wigm = StvCounter.count(
                roster, ballots, 3, new StvRuleset(DroopQuota.INSTANCE, WigmTransfer.INSTANCE, rounding, tieBreak));

        assertThat(fractional.quota()).isEqualTo(Fraction.of(13));
        for (int i = 0; i < 3; i++) {
            assertThat(fractional.rounds().get(i).tallies()).isEqualTo(wigm.rounds().get(i).tallies());
            assertThat(fractional.rounds().get(i).exhaustedVotes()).isEqualTo(wigm.rounds().get(i).exhaustedVotes());
        }
        assertThat(fractional.rounds().get(3).exhaustedVotes()).isEqualTo(Fraction.of(6607, 1000));
        assertThat(wigm.rounds().get(3).exhaustedVotes()).isEqualTo(Fraction.of(6520, 1000));
        assertThat(fractional.rounds().get(3).exhaustedVotes()).isNotEqualTo(wigm.rounds().get(3).exhaustedVotes());
        assertThat(fractional.rounds().get(3).tallies()).containsEntry(y, wigm.rounds().get(3).tallies().get(y));
    }

    /**
     * Hand-derived 5-candidate/3-seat count (105 ballots) demonstrating LastParcelTransfer's
     * defining behavior: when a candidate is elected, only their single most-recently-arrived
     * parcel is examined for a surplus — everything they held before that stays with them,
     * unchanged, for good. Ballot groups:
     * <pre>
     *   35: P,X      15: Q,X,Y     15: X,Y      20: Y      20: Z
     * </pre>
     * Total 105, quota = floor(105/4)+1 = 27.
     *
     * <p>Round 1 — P (35) meets quota, surplus 8, ratio 8/35 (P's ballots are all one parcel,
     *   value 1, so this is the same either way). X gains 35*(8/35)=8 -&gt; 15+8=23 (still below
     *   quota). This transfer creates X's "parcel 1": 35 ballots at value 8/35, ranked P,X
     *   only (no further preference).
     * <p>Round 2 — none meet quota (Q=15, X=23, Y=20, Z=20); Q (lowest) eliminated in full to
     *   X: 15 ballots at value 1, ranked Q,X,Y. This is X's "parcel 2" (the most recent).
     *   X -&gt; 23+15=38.
     * <p>Round 3 — X (38) meets quota, surplus 11. X now holds three parcels: its own
     *   original 15 (ranked X,Y), parcel 1 (35 at 8/35, dead-ends at X), and parcel 2 (15 at
     *   value 1, ranked Q,X,Y — the most recent arrival). Only parcel 2 is touched: ratio =
     *   11/15, so those 15 ballots move to Y at value 11/15, contributing exactly the surplus
     *   (11) to Y. X's own ballots and parcel 1 are untouched. X's final tally is therefore
     *   15 + 8 + 0 = 23 — <em>not</em> the quota (27), which is the well-known fairness
     *   trade-off of a last-parcel-only method versus an inclusive one like WIGM (which
     *   always leaves exactly quota behind).
     * <p>Round 4 — continuing {Y, Z}: Y (20+11=31) meets quota and is elected for the final
     *   seat.
     */
    @Test
    void lastParcelTransferOnlyTouchesTheMostRecentlyArrivedParcel() {
        Candidate p = Candidate.of("P");
        Candidate q = Candidate.of("Q");
        Candidate x = Candidate.of("X");
        Candidate y = Candidate.of("Y");
        Candidate z = Candidate.of("Z");
        List<Ballot> ballots = List.of(
                ballot(35, p, x),
                ballot(15, q, x, y),
                ballot(15, x, y),
                ballot(20, y),
                ballot(20, z));
        List<Candidate> roster = List.of(p, q, x, y, z);
        StvRuleset ruleset = new StvRuleset(
                DroopQuota.INSTANCE, LastParcelTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(roster));

        StvResult result = StvCounter.count(roster, ballots, 3, ruleset);

        assertThat(result.quota()).isEqualTo(Fraction.of(27));
        assertThat(result.electedInOrder()).containsExactly(p, x, y);

        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.tallies()).isEqualTo(
                Map.of(p, Fraction.of(35), q, Fraction.of(15), x, Fraction.of(15), y, Fraction.of(20), z, Fraction.of(20)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(p, Fraction.of(8)));

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies()).isEqualTo(
                Map.of(q, Fraction.of(15), x, Fraction.of(23), y, Fraction.of(20), z, Fraction.of(20)));
        assertThat(round2.action()).isEqualTo(new RoundAction.Eliminated(q));

        RoundRecord round3 = result.rounds().get(2);
        assertThat(round3.tallies()).isEqualTo(Map.of(x, Fraction.of(38), y, Fraction.of(20), z, Fraction.of(20)));
        assertThat(round3.action()).isEqualTo(new RoundAction.Elected(x, Fraction.of(11)));

        RoundRecord round4 = result.rounds().get(3);
        // X's parcel-1 (8) and own ballots (15) stay with X untouched -> X settles at 23, not
        // 27 (quota) -- the documented trade-off of last-parcel-only methods.
        assertThat(round4.tallies()).isEqualTo(Map.of(y, Fraction.of(31), z, Fraction.of(20)));
        // Y fills the last seat, so the count ends there: Y's surplus of 4 isn't transferred.
        assertThat(round4.action()).isEqualTo(new RoundAction.Elected(y, Fraction.ZERO));
    }

    /**
     * Degenerate single-candidate/single-seat case: the lone candidate is the only one who
     * could possibly fill the only seat, so the "continuing == remaining seats" shortcut
     * elects them in round 1 without ever computing a quota or transferring anything.
     */
    @Test
    void singleCandidateSingleSeatIsElectedWithoutAnyTransfer() {
        StvResult result = StvCounter.count(List.of(A), List.of(ballot(10, A)), 1, ruleset(A));

        assertThat(result.rounds()).hasSize(1);
        assertThat(result.electedInOrder()).containsExactly(A);
        RoundRecord round1 = result.rounds().getFirst();
        assertThat(round1.tallies()).isEqualTo(Map.of(A, Fraction.of(10)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(A, Fraction.ZERO));
    }

    /**
     * A single-seat race where one candidate's first-preference lead is so dominant (95 of
     * 100 votes) that they're elected outright in round 1 -- the "meets quota" main branch,
     * not the "continuing == remaining seats" shortcut above (3 candidates remain continuing
     * against 1 seat, so the shortcut doesn't apply here). The other two candidates' ballots
     * are never touched, and the count ends the instant the only seat is filled.
     */
    @Test
    void dominantFirstPreferenceWinnerIsElectedInRoundOneWithoutFurtherCounting() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Ballot> ballots = List.of(ballot(95, a), ballot(3, b), ballot(2, c));

        StvResult result = StvCounter.count(List.of(a, b, c), ballots, 1, ruleset(a, b, c));

        assertThat(result.quota()).isEqualTo(Fraction.of(51));
        assertThat(result.rounds()).hasSize(1);
        assertThat(result.electedInOrder()).containsExactly(a);
        RoundRecord round1 = result.rounds().getFirst();
        assertThat(round1.tallies()).isEqualTo(Map.of(a, Fraction.of(95), b, Fraction.of(3), c, Fraction.of(2)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(a, Fraction.ZERO));
    }

    /**
     * Confirms the "continuing == remaining seats" shortcut can trigger mid-count, after
     * elimination has whittled the field down -- not just when it's already true from round 1
     * (as in {@link #shortcutElectsRemainingContinuingCandidatesWithoutTransfer}). 4
     * candidates, 2 seats, 40 ballots:
     * <pre>
     *   13: A     13: B     8: C     6: D
     * </pre>
     * Quota = floor(40/3)+1 = 14 -- nobody ever reaches it. D (6, lowest) is eliminated first,
     * exhausting its ballots (no further preference); then C (8, now lowest) is eliminated the
     * same way. That leaves exactly {A, B} continuing for the 2 remaining seats, so both are
     * declared elected by the shortcut despite neither ever having met quota -- each gets its
     * own round, tied on 13 votes each and broken by declared order (A before B).
     */
    @Test
    void eliminationCanShrinkTheFieldUntilTheShortcutTakesOver() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        List<Ballot> ballots = List.of(ballot(13, a), ballot(13, b), ballot(8, c), ballot(6, d));

        StvResult result = StvCounter.count(List.of(a, b, c, d), ballots, 2, ruleset(a, b, c, d));

        assertThat(result.quota()).isEqualTo(Fraction.of(14));
        assertThat(result.rounds()).hasSize(4);
        assertThat(result.electedInOrder()).containsExactly(a, b);

        assertThat(result.rounds().get(0).action()).isEqualTo(new RoundAction.Eliminated(d));
        assertThat(result.rounds().get(1).action()).isEqualTo(new RoundAction.Eliminated(c));

        RoundRecord round3 = result.rounds().get(2);
        assertThat(round3.tallies()).isEqualTo(Map.of(a, Fraction.of(13), b, Fraction.of(13)));
        assertThat(round3.action()).isEqualTo(new RoundAction.Elected(a, Fraction.ZERO));
        assertThat(round3.exhaustedVotes()).isEqualTo(Fraction.of(14));

        RoundRecord round4 = result.rounds().get(3);
        assertThat(round4.action()).isEqualTo(new RoundAction.Elected(b, Fraction.ZERO));
        assertThat(round4.exhaustedVotes()).isEqualTo(Fraction.of(14));
    }

    /**
     * Hand-derived 5-candidate/2-seat count (34 ballots) exercising the same building blocks
     * as the bundled {@code hare-clark-qpq} ruleset (Droop quota, last-parcel-only transfer,
     * truncate:2 rounding) — but this is <strong>not</strong> validated against any real
     * election or third-party implementation: no reference implementation of this exact
     * combination could be found (droop's and OpenSTV's own "QPQ" modules both implement a
     * fundamentally different algorithm, Woodall's Quota Preferential by Quotient, not this
     * one — see the {@code hare-clark-qpq} entry in the bundled-rulesets table in README.md),
     * and the Tasmanian Electoral Commission's own published count sheets don't include the
     * underlying ballot-level preference data needed to replay one. This is a
     * self-consistency check only: the expected figures below are derived and verified by
     * hand, the same way {@code StvCounterTest}'s other hand-derived cases are, not sourced
     * externally.
     *
     * <pre>
     *   8: A,B      7: B      4: C      6: D,A,E      9: E
     * </pre>
     * Total 34, Droop quota = floor(34/3)+1 = 12.
     *
     * <p>Round 1 — first preferences A=8, B=7, C=4, D=6, E=9; nobody meets quota; C (lowest)
     * is excluded, its 4 ballots have no further preference and exhaust.
     * <p>Round 2 — B=7, D=6, A=8, E=9; D (lowest) is excluded; D's 6 ballots (ranked D,A,E)
     * move to A as one newly-arrived parcel, taking A to 8+6=14, past quota -- A is elected
     * and frozen (last-parcel transfer methods defer every surplus transfer).
     * <p>Round 3 — A's surplus (14-12=2) is transferred. This is the case the whole ruleset
     * exists to exercise: A now holds <em>two</em> parcels — the original 8 (ranked A,B) and
     * the newly-arrived 6 (ranked D,A,E) — and last-parcel-only examines <em>only</em> the
     * more recently arrived one. Ratio = 2 / 6 = 0.333..., truncated to 2dp = 0.33; each of
     * the 6 papers becomes worth 0.33 and moves to E (6 * 0.33 = 1.98), landing E at
     * 9 + 1.98 = 10.98. A's original 8 (ranked A,B) are <em>not</em> touched at all, even
     * though B is still continuing and would ordinarily be eligible to receive some of them
     * under an inclusive method (WIGM/fractional) -- confirming the transfer really did
     * examine only the last-arrived parcel. A's own reported total for this round stays at
     * the full 14 (not reduced to quota), matching the same last-parcel quirk already
     * observed against real ERS97 model election data elsewhere in this project.
     * <p>Round 4 — B=7, E=10.98; B (lowest) is excluded, its 7 ballots have no further
     * preference and exhaust.
     * <p>Round 5 — only E remains continuing for the 1 remaining seat: elected without
     * further transfer (the "remaining continuing == remaining seats" shortcut).
     * <p>Final result: A and E elected; C's 4 and B's 7 (11 total) exhausted.
     */
    @Test
    void handDerivedHareClarkStyleLastParcelSurplusIsolatesOnlyTheMostRecentParcel() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        Candidate e = Candidate.of("E");
        List<Ballot> ballots = List.of(
                ballot(8, a, b),
                ballot(7, b),
                ballot(4, c),
                ballot(6, d, a, e),
                ballot(9, e));
        StvRuleset hareClarkStyleRuleset = new StvRuleset(
                DroopQuota.INSTANCE,
                LastParcelTransfer.INSTANCE,
                new TruncateDecimal(2),
                new PreviousRoundVotesTieBreak(new DeclaredOrderTieBreak(List.of(a, b, c, d, e))));

        StvResult result = StvCounter.count(List.of(a, b, c, d, e), ballots, 2, hareClarkStyleRuleset);

        assertThat(result.quota()).isEqualTo(Fraction.of(12));
        assertThat(result.electedInOrder()).containsExactly(a, e);
        assertThat(result.rounds()).hasSize(5);

        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.tallies()).isEqualTo(
                Map.of(a, Fraction.of(8), b, Fraction.of(7), c, Fraction.of(4), d, Fraction.of(6), e, Fraction.of(9)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Eliminated(c));
        assertThat(round1.exhaustedVotes()).isEqualTo(Fraction.ZERO);

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies())
                .isEqualTo(Map.of(a, Fraction.of(8), b, Fraction.of(7), d, Fraction.of(6), e, Fraction.of(9)));
        assertThat(round2.action()).isEqualTo(new RoundAction.Eliminated(d));
        assertThat(round2.exhaustedVotes()).isEqualTo(Fraction.of(4));

        RoundRecord round3 = result.rounds().get(2);
        assertThat(round3.tallies()).isEqualTo(Map.of(b, Fraction.of(7), e, Fraction.of(9), a, Fraction.of(14)));
        assertThat(round3.action()).isEqualTo(new RoundAction.Elected(a, Fraction.of(2)));
        assertThat(round3.exhaustedVotes()).isEqualTo(Fraction.of(4));

        RoundRecord round4 = result.rounds().get(3);
        assertThat(round4.tallies()).isEqualTo(Map.of(b, Fraction.of(7), e, Fraction.of(549, 50))); // 10.98
        assertThat(round4.action()).isEqualTo(new RoundAction.Eliminated(b));
        assertThat(round4.exhaustedVotes()).isEqualTo(Fraction.of(4));

        RoundRecord round5 = result.rounds().get(4);
        assertThat(round5.tallies()).isEqualTo(Map.of(e, Fraction.of(549, 50))); // 10.98
        assertThat(round5.action()).isEqualTo(new RoundAction.Elected(e, Fraction.ZERO));
        assertThat(round5.exhaustedVotes()).isEqualTo(Fraction.of(11));
    }

    /**
     * Hand-derived 4-candidate/2-seat count (100 ballots) exercising {@link HareQuota} end to
     * end through {@link StvCounter} for the first time — previously it was only unit-tested
     * in isolation ({@code QuotaTest}). Unlike the hare-clark-qpq fixture above, this one
     * <em>is</em> independently cross-checked: droop's WIGM rule only ever computes Droop, and
     * OpenSTV's own Hare-quota code turned out to be unreachable dead code that also silently
     * adds a "+1" no textbook Hare quota has — but the CRAN package
     * <a href="https://cran.r-project.org/package=STV">{@code STV}</a> (Emerson et al., Yale,
     * actively maintained) genuinely implements it: {@code stv(..., quotaMethod = "Hare",
     * quotaFloor = FALSE)} computes the exact same unrounded {@code votes / seats} quota this
     * project does, with a {@code surplusMethod = "Fractional"} option matching {@link
     * FractionalTransfer} exactly. This fixture's own ballot data, run through that package,
     * reproduces every one of the round-by-round figures below exactly — same winners, same
     * quota, same eliminations, same 50-vote exact-quota election with zero surplus.
     * <pre>
     *   50: A      24: B      6: C,B      20: D
     * </pre>
     * Total 100, Hare quota = 100/2 = 50 exactly -- contrast Droop, which would have been
     * floor(100/3)+1 = 34 for this same data, giving A a 16-vote surplus to transfer
     * immediately. Under the larger Hare quota, A's 50 votes land exactly on quota, so A is
     * elected with <em>zero</em> surplus instead.
     * <p>Round 1 -- A=50, B=24, C=6, D=20; A meets the 50-vote quota exactly, surplus 0.
     * <p>Round 2 -- continuing {B, C, D} (3 &gt; 1 remaining seat, no shortcut yet); nobody
     * meets quota; C (lowest, 6) is excluded, its ballots (ranked C, B) move to B in full:
     * B rises to 24 + 6 = 30.
     * <p>Round 3 -- continuing {B, D} (2 &gt; 1, still no shortcut); nobody meets quota
     * (B=30, D=20); D (lowest) is excluded, its ballots have no further preference and
     * exhaust.
     * <p>Round 4 -- only B remains continuing for the 1 remaining seat: elected via the
     * "remaining continuing == remaining seats" shortcut, without ever needing to reach the
     * 50-vote quota through computation.
     */
    @Test
    void handDerivedHareQuotaFourCandidateTwoSeatCount() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        List<Ballot> ballots = List.of(ballot(50, a), ballot(24, b), ballot(6, c, b), ballot(20, d));
        StvRuleset hareRuleset = new StvRuleset(
                HareQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(a, b, c, d)));

        StvResult result = StvCounter.count(List.of(a, b, c, d), ballots, 2, hareRuleset);

        assertThat(result.quota()).isEqualTo(Fraction.of(50));
        assertThat(result.electedInOrder()).containsExactly(a, b);
        assertThat(result.rounds()).hasSize(4);

        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.tallies())
                .isEqualTo(Map.of(a, Fraction.of(50), b, Fraction.of(24), c, Fraction.of(6), d, Fraction.of(20)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(a, Fraction.ZERO));

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies()).isEqualTo(Map.of(b, Fraction.of(24), c, Fraction.of(6), d, Fraction.of(20)));
        assertThat(round2.action()).isEqualTo(new RoundAction.Eliminated(c));

        RoundRecord round3 = result.rounds().get(2);
        assertThat(round3.tallies()).isEqualTo(Map.of(b, Fraction.of(30), d, Fraction.of(20)));
        assertThat(round3.action()).isEqualTo(new RoundAction.Eliminated(d));

        RoundRecord round4 = result.rounds().get(3);
        assertThat(round4.tallies()).isEqualTo(Map.of(b, Fraction.of(30)));
        assertThat(round4.action()).isEqualTo(new RoundAction.Elected(b, Fraction.ZERO));
    }

    /**
     * A second {@link HareQuota} case, also cross-checked against the CRAN {@code STV}
     * package (see the fixture above) -- this one exercises a genuine fractional surplus
     * transfer, which the other's exact-quota-match (zero surplus) doesn't.
     * <pre>
     *   60: A,B      25: B      15: C
     * </pre>
     * Total 100, Hare quota = 50. A's 60 votes exceed quota by 10; all 60 of A's ballots rank
     * B next, so B gains the full surplus: 60 * (10/60) = 10, landing at 25 + 10 = 35.
     */
    @Test
    void handDerivedHareQuotaSurplusTransferMatchesTheRPackage() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Ballot> ballots = List.of(ballot(60, a, b), ballot(25, b), ballot(15, c));
        StvRuleset hareRuleset = new StvRuleset(
                HareQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(a, b, c)));

        StvResult result = StvCounter.count(List.of(a, b, c), ballots, 2, hareRuleset);

        assertThat(result.quota()).isEqualTo(Fraction.of(50));
        assertThat(result.electedInOrder()).containsExactly(a, b);
        assertThat(result.rounds()).hasSize(3);

        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.tallies())
                .isEqualTo(Map.of(a, Fraction.of(60), b, Fraction.of(25), c, Fraction.of(15)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(a, Fraction.of(10)));

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies()).isEqualTo(Map.of(b, Fraction.of(35), c, Fraction.of(15)));
        assertThat(round2.action()).isEqualTo(new RoundAction.Eliminated(c));

        RoundRecord round3 = result.rounds().get(2);
        assertThat(round3.tallies()).isEqualTo(Map.of(b, Fraction.of(35)));
        assertThat(round3.action()).isEqualTo(new RoundAction.Elected(b, Fraction.ZERO));
    }

    /**
     * Hand-derived 4-candidate/2-seat count (90 ballots) exercising {@link ImperialiQuota} end
     * to end through {@link StvCounter} for the first time — previously only unit-tested in
     * isolation. Self-consistency only, for the same reason as the Hare fixture above: neither
     * droop nor OpenSTV implement the Imperiali quota under any name.
     * <pre>
     *   25: A      25: B      25: C      15: D
     * </pre>
     * Total 90, Imperiali quota = floor(90 / (2+2)) = 22 -- contrast Droop, which would have
     * been floor(90/3)+1 = 31 for this same data. Imperiali's smaller quota is exactly what
     * makes it a poor real-world choice: unlike Droop, it doesn't mathematically guarantee no
     * more candidates can reach quota than there are seats, and this election demonstrates
     * that flaw directly -- three candidates (A, B, and C, all on 25) meet the 22-vote quota
     * in the very same round, but there are only 2 seats.
     * <p>Round 1 -- A=25, B=25, C=25, D=15; A, B, and C all meet quota, but only the top 2
     * (by tally, ties broken by declared order: A before B before C) are declared elected this
     * stage -- C is <em>not</em> elected despite also meeting quota, simply because the 2
     * seats are already accounted for by A and B. A's surplus (3) has no further preference to
     * transfer to (a single-preference ballot), so it's simply lost to non-transferability.
     * <p>Round 2 (same stage, B's turn) -- B's surplus (3) is likewise lost; both seats are
     * now filled, so the count ends here -- C never gets a further look, even though C's own
     * 25 votes exceed the 22-vote quota just as much as A's and B's did.
     */
    @Test
    void handDerivedImperialiQuotaMoreCandidatesThanSeatsMeetQuotaAtOnce() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        List<Ballot> ballots = List.of(ballot(25, a), ballot(25, b), ballot(25, c), ballot(15, d));
        StvRuleset imperialiRuleset = new StvRuleset(
                ImperialiQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(a, b, c, d)));

        StvResult result = StvCounter.count(List.of(a, b, c, d), ballots, 2, imperialiRuleset);

        assertThat(result.quota()).isEqualTo(Fraction.of(22));
        assertThat(result.electedInOrder()).containsExactly(a, b);
        assertThat(result.rounds()).hasSize(2);

        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.tallies())
                .isEqualTo(Map.of(a, Fraction.of(25), b, Fraction.of(25), c, Fraction.of(25), d, Fraction.of(15)));
        // A and B fill both seats in round 1, so the count ends without moving either surplus.
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(a, Fraction.ZERO));

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies())
                .isEqualTo(Map.of(c, Fraction.of(25), d, Fraction.of(15), b, Fraction.of(25)));
        assertThat(round2.action()).isEqualTo(new RoundAction.Elected(b, Fraction.ZERO));
    }

    /**
     * Hand-derived 4-candidate/2-seat count exercising tied rankings under {@link
     * FractionalTransfer} for the first time -- specifically, the most distinctive and
     * easy-to-miss part of the equal-preference algorithm verified against CRAN's {@code vote}
     * package (see {@link StvCounter}'s class javadoc): when one of two candidates tied for a
     * ballot's front-most rank is <em>eliminated</em>, the survivor inherits the ballot's
     * <em>entire</em> pooled value next round, not just the half-share it had been crediting
     * while the tie lasted.
     * <pre>
     *   10: A=B (tied)      3: C      40: D
     * </pre>
     * Total 53, Droop quota = floor(53/3)+1 = 18.
     * <p>Round 1 -- first preferences: the 10 tied ballots split evenly, crediting A=5 and B=5;
     * C=3; D=40. D alone meets quota; D's own ballots rank nobody else, so the 22-vote surplus
     * has nowhere to go and is simply lost to non-transferability.
     * <p>Round 2 -- continuing {A, B, C} (3 &gt; 1 remaining seat); nobody meets quota; C
     * (lowest, 3) is excluded, its ballot has no further preference and exhausts.
     * <p>Round 3 -- continuing {A, B} (2 &gt; 1, still no shortcut); A and B are tied at 5
     * each -- a genuine candidate-tally tie, broken by declared order (A eliminated first).
     * Critically, A was one of the two candidates <em>tied on the ballot itself</em>: A's
     * departure doesn't move those 10 ballots' value anywhere (elimination is never
     * discounted) -- it just leaves B as the sole remaining candidate at that same rank.
     * <p>Round 4 -- only B remains continuing for the 1 remaining seat: elected via the
     * "remaining continuing == remaining seats" shortcut, with a tally of the <em>full</em>
     * 10 (not 5) -- the pooled value the ballot always carried, now entirely B's since nobody
     * else remains to share it with.
     */
    @Test
    void handDerivedTiedRankingSurvivorInheritsTheFullPoolAfterACoHolderIsEliminated() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        Ballot tiedBallot = new Ballot(List.of(Set.of(a, b)), BigInteger.TEN);
        List<Ballot> ballots = List.of(tiedBallot, ballot(3, c), ballot(40, d));
        StvRuleset fractionalRuleset = new StvRuleset(
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(a, b, c, d)));

        StvResult result = StvCounter.count(List.of(a, b, c, d), ballots, 2, fractionalRuleset);

        assertThat(result.quota()).isEqualTo(Fraction.of(18));
        assertThat(result.electedInOrder()).containsExactly(d, b);
        assertThat(result.rounds()).hasSize(4);

        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.tallies())
                .isEqualTo(Map.of(a, Fraction.of(5), b, Fraction.of(5), c, Fraction.of(3), d, Fraction.of(40)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(d, Fraction.of(22)));

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies()).isEqualTo(Map.of(a, Fraction.of(5), b, Fraction.of(5), c, Fraction.of(3)));
        assertThat(round2.action()).isEqualTo(new RoundAction.Eliminated(c));

        RoundRecord round3 = result.rounds().get(2);
        assertThat(round3.tallies()).isEqualTo(Map.of(a, Fraction.of(5), b, Fraction.of(5)));
        assertThat(round3.action()).isEqualTo(new RoundAction.Eliminated(a));

        RoundRecord round4 = result.rounds().get(3);
        assertThat(round4.tallies()).isEqualTo(Map.of(b, Fraction.of(10)));
        assertThat(round4.action()).isEqualTo(new RoundAction.Elected(b, Fraction.ZERO));
    }

    /**
     * A second tied-ranking case, exercising the other half of the equal-preference algorithm:
     * when one of two tied-for-front candidates is instead <em>elected with a surplus</em>,
     * only their own share of the ballot gets scaled down by the surplus ratio -- the other
     * tied candidate's share is added back unscaled, and the ballot's updated pooled value
     * stays with that survivor going forward.
     * <pre>
     *   20: A=B (tied)      15: A      5: C
     * </pre>
     * Total 40, Droop quota = floor(40/3)+1 = 14. First preferences: the tied 20 split evenly
     * (A=10, B=10), plus A's own 15 unshared ballots, giving A = 15+10 = 25; B=10; C=5.
     * <p>Round 1 -- only A meets quota (25). Surplus = 25-14 = 11, ratio = 11/25. A holds two
     * distinct parcels: the 15 unshared ballots (full share 1, no further preference -- their
     * reduced residual is simply lost) and the 20 tied ballots (A's own share 1/2, which
     * <em>does</em> have somewhere to go, since B still holds the other half). The tied
     * ballots' new pooled value is B's own untouched half (1/2) plus A's ratio-scaled half
     * ((1/2)*(11/25) = 11/50): 1/2 + 11/50 = 18/25 each, all now credited solely to B.
     * <p>Round 2 -- continuing {B, C}; B's tally is 20*(18/25) = 72/5 = 14.4, C's is 5. B alone
     * meets the 14-vote quota, with a surplus of 72/5 - 14 = 2/5. Both seats are now filled.
     */
    @Test
    void handDerivedTiedRankingOtherCoHoldersShareSurvivesAnElectedCoHoldersSurplus() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Ballot tiedBallot = new Ballot(List.of(Set.of(a, b)), BigInteger.valueOf(20));
        List<Ballot> ballots = List.of(tiedBallot, ballot(15, a), ballot(5, c));
        StvRuleset fractionalRuleset = new StvRuleset(
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(a, b, c)));

        StvResult result = StvCounter.count(List.of(a, b, c), ballots, 2, fractionalRuleset);

        assertThat(result.quota()).isEqualTo(Fraction.of(14));
        assertThat(result.electedInOrder()).containsExactly(a, b);
        assertThat(result.rounds()).hasSize(2);

        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.tallies())
                .isEqualTo(Map.of(a, Fraction.of(25), b, Fraction.of(10), c, Fraction.of(5)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(a, Fraction.of(11)));

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies()).isEqualTo(Map.of(b, Fraction.of(72, 5), c, Fraction.of(5)));
        // B fills the last seat on 14.4: the count ends, B's surplus untransferred.
        assertThat(round2.action()).isEqualTo(new RoundAction.Elected(b, Fraction.ZERO));
    }

    /**
     * The bundled {@code scottish} preset's tied-ranking policy ({@code truncate}, per SSI
     * 2007/42 rule 43(1)(c)) against {@code skip} on the same ballots -- chosen so the two
     * policies elect <em>different</em> winners, showing the choice isn't cosmetic. Both
     * policies were also cross-checked against OpenSTV's own {@code ScottishSTV} on synthetic
     * ballot sets containing hundreds of tied rankings: {@code truncate} against its "San
     * Francisco" overvote cleaning, {@code skip} against its "Cambridge" cleaning, matching
     * quota, winners, and every round's tallies exactly.
     * <pre>
     *   4: A=B, C      3: C, A=B      5: A      4: B      (1 seat)
     * </pre>
     * <p>Truncate: the 4 "A=B, C" ballots tie at first preference and are void, so they don't
     * count toward the quota at all; "C, A=B" becomes just "C". Valid votes 12, quota
     * floor(12/2)+1 = 7. First preferences A=5, B=4, C=3 -- nobody reaches quota, C and then B
     * are excluded (their ballots have nowhere to go), and A takes the seat.
     * <p>Skip: both tied ranks are dropped instead, so "A=B, C" and "C, A=B" both become just
     * "C". Valid votes 16, quota floor(16/2)+1 = 9. First preferences A=5, B=4, C=7 -- B is
     * excluded first, then A, and <em>C</em> takes the seat.
     */
    @Test
    void scottishPresetVoidsFirstRankTiesAndSkipPolicyElectsSomeoneElse() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Candidate> candidates = List.of(a, b, c);
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(a, b), Set.of(c)), BigInteger.valueOf(4)),
                new Ballot(List.of(Set.of(c), Set.of(a, b)), BigInteger.valueOf(3)),
                ballot(5, a),
                ballot(4, b));
        ElectionConfig scottish = ConfigLoader.loadResource("/rulesets/scottish.yaml");

        StvRuleset truncating = StvRulesetParser.parse(scottish.quota(), scottish.transfer(), scottish.rounding(),
                scottish.tiebreak(), scottish.batchExclusion(), scottish.tiedRankings(), candidates);
        assertThat(truncating.tiedRankings()).isEqualTo(TiedRankingPolicy.TRUNCATE);
        StvResult truncated = StvCounter.count(candidates, ballots, 1, truncating);

        assertThat(truncated.quota()).isEqualTo(Fraction.of(7));
        assertThat(truncated.rounds().getFirst().tallies())
                .isEqualTo(Map.of(a, Fraction.of(5), b, Fraction.of(4), c, Fraction.of(3)));
        assertThat(truncated.electedInOrder()).containsExactly(a);

        StvRuleset skipping = StvRulesetParser.parse(scottish.quota(), scottish.transfer(), scottish.rounding(),
                scottish.tiebreak(), scottish.batchExclusion(), "skip", candidates);
        StvResult skipped = StvCounter.count(candidates, ballots, 1, skipping);

        assertThat(skipped.quota()).isEqualTo(Fraction.of(9));
        assertThat(skipped.rounds().getFirst().tallies())
                .isEqualTo(Map.of(a, Fraction.of(5), b, Fraction.of(4), c, Fraction.of(7)));
        assertThat(skipped.electedInOrder()).containsExactly(c);
    }

    /**
     * Tasmania's formality rule (Electoral Act 2004, s.102-103) -- truncate at the first tied
     * rank, then anything left with fewer than the minimum number of preferences is informal
     * -- with a minimum of 3, as for a Tasmanian council election.
     * <pre>
     *   6: A,B,C        formal (exactly 3)
     *   4: A,B          informal: too short, no tie involved at all
     *   5: B,C=D,E      informal: truncated at the tie to just "B", inside the minimum
     *   3: C,D,E,A=B    formal: the tie is above the minimum, so it's just truncated to C,D,E
     *   2: D,E,A        formal
     * </pre>
     * Valid votes 6+3+2 = 11, Droop quota floor(11/2)+1 = 6; first preferences A=6, C=3, D=2
     * (B and E have none left), so A meets quota at once. Counting every ballot instead would
     * have given 20 valid votes and a quota of 11 that nobody reaches on first preferences.
     */
    @Test
    void minimumPreferencesMakesShortOrEarlyTiedBallotsInformal() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        Candidate e = Candidate.of("E");
        List<Candidate> candidates = List.of(a, b, c, d, e);
        List<Ballot> ballots = List.of(
                ballot(6, a, b, c),
                ballot(4, a, b),
                new Ballot(List.of(Set.of(b), Set.of(c, d), Set.of(e)), BigInteger.valueOf(5)),
                new Ballot(List.of(Set.of(c), Set.of(d), Set.of(e), Set.of(a, b)), BigInteger.valueOf(3)),
                ballot(2, d, e, a));
        StvRuleset ruleset = new StvRuleset(DroopQuota.INSTANCE, WigmTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(candidates), false, TiedRankingPolicy.TRUNCATE, 3);

        StvResult result = StvCounter.count(candidates, ballots, 1, ruleset);

        assertThat(result.quota()).isEqualTo(Fraction.of(6));
        assertThat(result.rounds().getFirst().tallies()).isEqualTo(Map.of(
                a, Fraction.of(6), b, Fraction.ZERO, c, Fraction.of(3), d, Fraction.of(2), e, Fraction.ZERO));
        assertThat(result.electedInOrder()).containsExactly(a);
    }

    /**
     * The minimum is capped at one fewer than the number of candidates, reproducing
     * Tasmania's council rule that with only 2 candidates a single "1" is enough (s.102(2)):
     * a minimum of 7 with 2 candidates still accepts one-preference ballots.
     */
    @Test
    void minimumPreferencesIsCappedAtOneFewerThanTheCandidates() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        StvRuleset ruleset = new StvRuleset(DroopQuota.INSTANCE, WigmTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(a, b)), false, TiedRankingPolicy.TRUNCATE, 7);

        StvResult result = StvCounter.count(List.of(a, b), List.of(ballot(3, a), ballot(2, b)), 1, ruleset);

        assertThat(result.quota()).isEqualTo(Fraction.of(3));
        assertThat(result.electedInOrder()).containsExactly(a);
    }

    @Test
    void refusesToCountWhenNoBallotIsFormal() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        StvRuleset ruleset = new StvRuleset(DroopQuota.INSTANCE, WigmTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(a, b, c)), false, TiedRankingPolicy.TRUNCATE, 2);

        assertThatThrownBy(() -> StvCounter.count(List.of(a, b, c), List.of(ballot(5, a), ballot(3, b)), 1, ruleset))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No valid votes");
    }

    /**
     * Under exact rounding, fractional transfers compound into ever-larger fractions when ballots
     * pass through many elected candidates, and the count warns about it (once) while it's still
     * running -- 40 candidates, 15 seats and 1,000 voters ranking about 20 each (with skewed
     * popularity, so surpluses arise early and keep passing on) is already enough.
     * The same count under truncate:9 never warns, nor does an ordinary small exact count.
     */
    @Test
    void warnsOnceWhenExactArithmeticStartsCompounding() {
        java.util.Random random = new java.util.Random(1);
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            candidates.add(Candidate.of("C" + i));
        }
        List<Ballot> ballots = new ArrayList<>();
        for (int voter = 0; voter < 1000; voter++) {
            // Skewed popularity, as in real elections (weight 1/i^0.8, drawn without replacement
            // by sorting on log-weight plus Gumbel noise): a few candidates reach quota early and
            // their surpluses pass on through many hands.
            Map<Candidate, Double> key = new java.util.HashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                key.put(candidates.get(i), -0.8 * Math.log(i + 1) - Math.log(-Math.log(random.nextDouble())));
            }
            List<Candidate> pool = new ArrayList<>(candidates);
            pool.sort(java.util.Comparator.comparing(key::get).reversed());
            int length = 1;
            while (length < pool.size() && random.nextDouble() < 19.0 / 20) {
                length++;
            }
            List<Set<Candidate>> ranking = new ArrayList<>();
            for (Candidate candidate : pool.subList(0, length)) {
                ranking.add(Set.of(candidate));
            }
            ballots.add(new Ballot(ranking, BigInteger.ONE));
        }

        List<String> warnings = new ArrayList<>();
        StvCounter.count(candidates, ballots, 15, fractional(Exact.INSTANCE, candidates), warnings::add);
        assertThat(warnings).singleElement().asString()
                .contains("-digit denominators by round")
                .contains("a truncating rounding rule would keep values bounded");

        List<String> truncated = new ArrayList<>();
        StvCounter.count(candidates, ballots, 15, fractional(new TruncateDecimal(9), candidates), truncated::add);
        assertThat(truncated).isEmpty();

        Candidate x = Candidate.of("X");
        Candidate y = Candidate.of("Y");
        Candidate z = Candidate.of("Z");
        List<String> small = new ArrayList<>();
        StvCounter.count(List.of(x, y, z), List.of(
                new Ballot(List.of(Set.of(x)), BigInteger.valueOf(5)),
                new Ballot(List.of(Set.of(y), Set.of(x)), BigInteger.valueOf(3)),
                new Ballot(List.of(Set.of(z), Set.of(x)), BigInteger.valueOf(3))),
                1, fractional(Exact.INSTANCE, List.of(x, y, z)), small::add);
        assertThat(small).isEmpty();
    }

    /**
     * The Senate's last two (s273(18)): one seat left, A on 5 and B on 4, both short of the quota
     * of 7 once C's 3 votes exhaust. A is elected there and then, on 5; B is never excluded.
     */
    @Test
    void senateElectsTheHigherOfTheLastTwo() {
        List<Candidate> candidates = List.of(A, B, C);
        StvRuleset senate = StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/senate.yaml"), candidates);

        StvResult result = StvCounter.count(candidates, List.of(ballot(5, A), ballot(4, B), ballot(3, C)), 1, senate);

        assertThat(result.quota()).isEqualTo(Fraction.of(7));
        assertThat(result.rounds()).extracting(RoundRecord::action).containsExactly(
                new RoundAction.Eliminated(C), new RoundAction.Elected(A, Fraction.ZERO));
        assertThat(result.rounds().getLast().tallies()).isEqualTo(Map.of(A, Fraction.of(5), B, Fraction.of(4)));
    }

    /**
     * And s273(17): two seats, quota 5, D's 2 votes exhaust, then excluding C leaves A and B for
     * the two seats. They're elected on 4 each, tied, so A first by declared order; C's 3 papers
     * never reach B, who would have been elected first on 7 had they moved.
     */
    @Test
    void senateElectsTheLastCandidatesAsTheyStand() {
        List<Candidate> candidates = List.of(A, B, C, D);
        StvRuleset senate = StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/senate.yaml"), candidates);

        StvResult result = StvCounter.count(candidates,
                List.of(ballot(4, A), ballot(4, B), ballot(3, C, B), ballot(2, D)), 2, senate);

        assertThat(result.quota()).isEqualTo(Fraction.of(5));
        assertThat(result.electedInOrder()).containsExactly(A, B);
        assertThat(result.rounds().getLast().tallies()).isEqualTo(Map.of(A, Fraction.of(4), B, Fraction.of(4)));
    }

    private static StvRuleset fractional(info.fkhodkov.rankedchoice.stv.rounding.RoundingRule rounding,
            List<Candidate> candidates) {
        return new StvRuleset(DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, rounding,
                new DeclaredOrderTieBreak(candidates));
    }
}
