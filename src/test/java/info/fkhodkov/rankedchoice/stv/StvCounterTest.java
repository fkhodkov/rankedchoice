package info.fkhodkov.rankedchoice.stv;

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
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
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
     * <p>Round 1 — tallies {A:40, B:30, C:10}; A elected, surplus 13, which exhausts in full
     *   (B unavailable, no further preference on those ballots).
     * <p>Round 2 — B elected using its untouched frozen tally of 30 (surplus 3), which also
     *   exhausts in full (B's own ballots have no second preference). C remains continuing
     *   throughout but never sees a transfer, since both seats are filled by A and B.
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
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(a, Fraction.of(13)));
        assertThat(round1.exhaustedVotes()).isEqualTo(Fraction.ZERO);

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.roundNumber()).isEqualTo(2);
        // B's own tally must still read 30 here — not inflated by A's just-applied surplus.
        assertThat(round2.tallies()).isEqualTo(Map.of(b, Fraction.of(30), c, Fraction.of(10)));
        assertThat(round2.action()).isEqualTo(new RoundAction.Elected(b, Fraction.of(3)));
        assertThat(round2.exhaustedVotes()).isEqualTo(Fraction.of(13));
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
        assertThat(result.rounds().get(1).action()).isEqualTo(new RoundAction.Elected(x, Fraction.of(2)));
    }

    @Test
    void rejectsTiedRankings() {
        Ballot tiedBallot = new Ballot(List.of(Set.of(A, B)), BigInteger.ONE);
        assertThatThrownBy(() -> StvCounter.count(List.of(A, B), List.of(tiedBallot), 1, ruleset(A, B)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMoreSeatsThanCandidates() {
        assertThatThrownBy(() -> StvCounter.count(List.of(A), List.of(ballot(1, A)), 2, ruleset(A)))
                .isInstanceOf(IllegalArgumentException.class);
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
        assertThat(round4.action()).isEqualTo(new RoundAction.Elected(y, Fraction.of(4)));
    }
}
