package info.fkhodkov.rankedchoice.qpq;

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
import info.fkhodkov.rankedchoice.stv.InformalBallots;
import info.fkhodkov.rankedchoice.stv.TiedRankingPolicy;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;

class QpqCounterTest {

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

    private static QpqRuleset ruleset(Candidate... declaredOrder) {
        return new QpqRuleset(new DeclaredOrderTieBreak(List.of(declaredOrder)));
    }

    /**
     * Hand-derived 3-candidate, 1-seat count (15 ballots) -- with only one seat, QPQ reduces
     * to a majority-runoff shape (like IRV), a useful basic sanity check before the more
     * QPQ-specific restart case below.
     * <pre>
     *   6: A      4: B      5: C,A
     * </pre>
     * Stage 1 (restart): first preferences A=6, B=4, C=5; tc=0 for everyone so quotients equal
     * these counts exactly. va=15, tx=0, quota = 15/(1+1-0) = 7.5. Nobody's quotient beats
     * quota; B (lowest, 4) is excluded. B's ballots have no further preference and exhaust.
     * <p>Stage 2 (restart, since an exclusion always triggers one): re-walking from scratch
     * against {A, C} -- A=6, C=5 (B's 4 ballots have nowhere to go, stay exhausted). va=11,
     * tx=0, quota = 11/(1+1-0) = 5.5. A's quotient (6) exceeds quota -- A is elected. Count
     * ends (1 seat, 1 winner).
     */
    @Test
    void handDerivedThreeCandidateOneSeatCountBehavesLikeAMajorityRunoff() {
        List<Ballot> ballots = List.of(ballot(6, A), ballot(4, B), ballot(5, C, A));

        QpqResult result = QpqCounter.count(List.of(A, B, C), ballots, 1, ruleset(A, B, C));

        assertThat(result.electedInOrder()).containsExactly(A);
        assertThat(result.rounds()).hasSize(2);

        QpqRoundRecord stage1 = result.rounds().get(0);
        assertThat(stage1.restarted()).isTrue();
        assertThat(stage1.quota()).isEqualTo(Fraction.of(15, 2));
        assertThat(stage1.quotients()).isEqualTo(Map.of(A, Fraction.of(6), B, Fraction.of(4), C, Fraction.of(5)));
        assertThat(stage1.action()).isEqualTo(new QpqAction.Excluded(B));

        QpqRoundRecord stage2 = result.rounds().get(1);
        assertThat(stage2.restarted()).isTrue();
        assertThat(stage2.quota()).isEqualTo(Fraction.of(11, 2));
        assertThat(stage2.quotients()).isEqualTo(Map.of(A, Fraction.of(6), C, Fraction.of(5)));
        assertThat(stage2.action()).isEqualTo(new QpqAction.Elected(A));
    }

    @Test
    void truncatedFirstRankTiesAreVoidSkippedTiesMoveOnAndSplitIsRejected() {
        List<Ballot> base = List.of(ballot(10, A), ballot(3, B, A), ballot(2, C), ballot(1, D));
        Ballot tiedFirst = new Ballot(List.of(Set.of(C, D), Set.of(B)), BigInteger.valueOf(4));
        List<Ballot> withTie = List.of(ballot(10, A), ballot(3, B, A), ballot(2, C), ballot(1, D), tiedFirst);

        // Truncate: the tied-first ballot is void, so the count is exactly the base count's.
        QpqRuleset truncating = new QpqRuleset(new DeclaredOrderTieBreak(List.of(A, B, C, D)), TiedRankingPolicy.TRUNCATE, 0);
        QpqResult truncated = QpqCounter.count(List.of(A, B, C, D), withTie, 2, truncating);
        QpqResult baseResult = QpqCounter.count(List.of(A, B, C, D), base, 2, ruleset(A, B, C, D));
        assertThat(truncated.rounds()).isEqualTo(baseResult.rounds());
        assertThat(truncated.electedInOrder()).isEqualTo(baseResult.electedInOrder());
        assertThat(truncated.informal())
                .isEqualTo(new InformalBallots(BigInteger.ZERO, BigInteger.valueOf(4), BigInteger.ZERO));

        // Skip: the tied rank is dropped and the ballot counts for B, exactly like 4 more "B" ballots.
        QpqRuleset skipping = new QpqRuleset(new DeclaredOrderTieBreak(List.of(A, B, C, D)), TiedRankingPolicy.SKIP, 0);
        List<Ballot> withBInstead = List.of(ballot(10, A), ballot(3, B, A), ballot(2, C), ballot(1, D), ballot(4, B));
        assertThat(QpqCounter.count(List.of(A, B, C, D), withTie, 2, skipping))
                .isEqualTo(QpqCounter.count(List.of(A, B, C, D), withBInstead, 2, ruleset(A, B, C, D)));

        QpqRuleset splitting = new QpqRuleset(new DeclaredOrderTieBreak(List.of(A, B, C, D)), TiedRankingPolicy.SPLIT, 0);
        assertThatThrownBy(() -> QpqCounter.count(List.of(A, B, C, D), withTie, 2, splitting))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("QPQ has no defined rule for splitting");
    }

    /**
     * Hand-derived 4-candidate, 2-seat count (16 ballots) exercising QPQ's distinctive
     * restart-after-exclusion rule -- specifically, the case where a previously-elected
     * candidate returns to hopeful during a restart and is legitimately re-elected.
     * <pre>
     *   10: A      3: B,A      2: C      1: D
     * </pre>
     * Stage 1 (restart): first preferences A=10, B=3, C=2, D=1; tc=0 for all, va=16, tx=0,
     * quota = 16/(1+2-0) = 16/3. A's quotient (10) exceeds quota -- A is elected. A's 10
     * ballots (ranked A only) get elected-credit 1/10 and, having no further preference,
     * become exhausted.
     * <p>Stage 2 (no restart -- only exclusions trigger one): hopeful is now {B, C, D}. B=3
     * (unaffected -- its own 3 ballots never touched A), C=2, D=1; tc=0 for all three (none of
     * their ballots carry any credit). va = 3+2+1 = 6 (A's 10 exhausted ballots excluded); tx =
     * 10 * 1/10 = 1 (those same ballots' banked credit). quota = 6/(1+2-1) = 6/2 = 3. B's
     * quotient is exactly 3 -- not <em>greater than</em> quota, so nobody is elected. D
     * (lowest, 1) is excluded; its ballot has no further preference and exhausts.
     * <p>Stage 3 (restart!): A returns to hopeful alongside B and C. Re-walking every ballot
     * from scratch (credit reset to zero) against {A, B, C}: A=10 again (back to its own
     * original support), B=3, C=2. va=15, tx=0 (D's exhausted ballot's credit was reset to
     * zero by the restart), quota = 15/(1+2-0) = 5. A's quotient (10) exceeds quota again --
     * A is legitimately elected a second time; this must <em>not</em> appear twice in the
     * final winner list.
     * <p>Stage 4 (no restart): A's 10 ballots are exhausted again (credit 1/10, same as
     * before). Hopeful is now {B, C}: B=3, C=2, tc=0 for both. va=5, tx=1 (A's ballots'
     * banked credit, same as stage 2's). quota = 5/(1+2-1) = 5/2 = 2.5. B's quotient (3)
     * exceeds quota -- B is elected. Both seats filled; count ends.
     * <p>Final winners: A and B (in that order -- the order the final, post-restart
     * elections happened in, not stage 1's now-superseded one). D excluded; C never wins.
     */
    @Test
    void handDerivedFourCandidateTwoSeatCountReElectsACandidateAfterARestart() {
        List<Ballot> ballots = List.of(ballot(10, A), ballot(3, B, A), ballot(2, C), ballot(1, D));

        QpqResult result = QpqCounter.count(List.of(A, B, C, D), ballots, 2, ruleset(A, B, C, D));

        assertThat(result.electedInOrder()).containsExactly(A, B);
        assertThat(result.rounds()).hasSize(4);

        QpqRoundRecord stage1 = result.rounds().get(0);
        assertThat(stage1.restarted()).isTrue();
        assertThat(stage1.quota()).isEqualTo(Fraction.of(16, 3));
        assertThat(stage1.action()).isEqualTo(new QpqAction.Elected(A));

        QpqRoundRecord stage2 = result.rounds().get(1);
        assertThat(stage2.restarted()).isFalse();
        assertThat(stage2.quota()).isEqualTo(Fraction.of(3));
        assertThat(stage2.quotients()).isEqualTo(Map.of(B, Fraction.of(3), C, Fraction.of(2), D, Fraction.of(1)));
        assertThat(stage2.action()).isEqualTo(new QpqAction.Excluded(D));

        QpqRoundRecord stage3 = result.rounds().get(2);
        assertThat(stage3.restarted()).isTrue();
        assertThat(stage3.quota()).isEqualTo(Fraction.of(5));
        assertThat(stage3.quotients()).isEqualTo(Map.of(A, Fraction.of(10), B, Fraction.of(3), C, Fraction.of(2)));
        assertThat(stage3.action()).isEqualTo(new QpqAction.Elected(A));

        QpqRoundRecord stage4 = result.rounds().get(3);
        assertThat(stage4.restarted()).isFalse();
        assertThat(stage4.quota()).isEqualTo(Fraction.of(5, 2));
        assertThat(stage4.quotients()).isEqualTo(Map.of(B, Fraction.of(3), C, Fraction.of(2)));
        assertThat(stage4.action()).isEqualTo(new QpqAction.Elected(B));
    }

    @Test
    void aSingleFeasibleOutcomeNeedsNoStageAtAll() {
        List<Ballot> ballots = List.of(ballot(5, A), ballot(3, B));

        QpqResult result = QpqCounter.count(List.of(A, B), ballots, 2, ruleset(A, B));

        assertThat(result.electedInOrder()).containsExactlyInAnyOrder(A, B);
        assertThat(result.rounds()).isEmpty();
    }

    @Test
    void rejectsTiedRankings() {
        List<Ballot> ballots = List.of(new Ballot(List.of(Set.of(A, B)), BigInteger.ONE));

        assertThatThrownBy(() -> QpqCounter.count(List.of(A, B), ballots, 1, ruleset(A, B)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("tied rankings");
    }

    @Test
    void rejectsMoreSeatsThanCandidates() {
        assertThatThrownBy(() -> QpqCounter.count(List.of(A), List.of(ballot(1, A)), 2, ruleset(A)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot fill");
    }

}
