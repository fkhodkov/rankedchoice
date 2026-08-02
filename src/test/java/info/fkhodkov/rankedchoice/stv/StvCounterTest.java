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
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;

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
}
