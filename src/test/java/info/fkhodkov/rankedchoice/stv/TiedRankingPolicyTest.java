package info.fkhodkov.rankedchoice.stv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.LastParcelTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.WigmTransfer;

class TiedRankingPolicyTest {

    private static final Candidate A = Candidate.of("A");
    private static final Candidate B = Candidate.of("B");
    private static final Candidate C = Candidate.of("C");
    private static final Candidate D = Candidate.of("D");

    // A, then B=C tied, then D
    private static final Ballot LATER_TIE = new Ballot(List.of(Set.of(A), Set.of(B, C), Set.of(D)), BigInteger.TWO);
    // A=B tied first, then C
    private static final Ballot FIRST_TIE = new Ballot(List.of(Set.of(A, B), Set.of(C)), BigInteger.ONE);
    private static final Ballot UNTIED = new Ballot(List.of(Set.of(D), Set.of(A)), BigInteger.TEN);

    @Test
    void defaultsToSplitUnderFractionalAndRejectOtherwise() {
        assertThat(TiedRankingPolicy.defaultFor(FractionalTransfer.INSTANCE)).isEqualTo(TiedRankingPolicy.SPLIT);
        assertThat(TiedRankingPolicy.defaultFor(WigmTransfer.INSTANCE)).isEqualTo(TiedRankingPolicy.REJECT);
        assertThat(TiedRankingPolicy.defaultFor(LastParcelTransfer.INSTANCE)).isEqualTo(TiedRankingPolicy.REJECT);
    }

    @Test
    void rejectThrowsOnlyWhenATieIsPresent() {
        assertThat(TiedRankingPolicy.REJECT.prepare(List.of(UNTIED), WigmTransfer.INSTANCE)).containsExactly(UNTIED);
        assertThatThrownBy(() -> TiedRankingPolicy.REJECT.prepare(List.of(UNTIED, LATER_TIE), WigmTransfer.INSTANCE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void splitIsOnlyDefinedForFractionalTransfer() {
        assertThat(TiedRankingPolicy.SPLIT.prepare(List.of(FIRST_TIE), FractionalTransfer.INSTANCE))
                .containsExactly(FIRST_TIE);
        assertThatThrownBy(() -> TiedRankingPolicy.SPLIT.prepare(List.of(FIRST_TIE), WigmTransfer.INSTANCE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void truncateStopsTheBallotAtItsFirstTiedRank() {
        List<Ballot> prepared = TiedRankingPolicy.TRUNCATE.prepare(
                List.of(LATER_TIE, FIRST_TIE, UNTIED), WigmTransfer.INSTANCE);

        assertThat(prepared).containsExactly(
                new Ballot(List.of(Set.of(A)), BigInteger.TWO),
                // A tie at the very first rank leaves nothing: a blank ballot, which the count
                // excludes from the valid vote -- the Scottish statute's "void" (rule 43(1)(c)).
                new Ballot(List.of(), BigInteger.ONE),
                UNTIED);
        assertThat(prepared.get(1).isBlank()).isTrue();
    }

    @Test
    void skipDropsEveryTiedRankAndKeepsTheRest() {
        List<Ballot> prepared = TiedRankingPolicy.SKIP.prepare(
                List.of(LATER_TIE, FIRST_TIE, UNTIED), WigmTransfer.INSTANCE);

        assertThat(prepared).containsExactly(
                new Ballot(List.of(Set.of(A), Set.of(D)), BigInteger.TWO),
                new Ballot(List.of(Set.of(C)), BigInteger.ONE),
                UNTIED);
    }
}
