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
        assertThat(result.rounds().get(1).tallies().get(B)).isEqualTo(Fraction.of(297, 50));
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
        assertThat(fractional.rounds().get(3).tallies().get(y)).isEqualTo(wigm.rounds().get(3).tallies().get(y));
    }
}
