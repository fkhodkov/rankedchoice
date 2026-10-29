package info.fkhodkov.rankedchoice.stv.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;

class StvJsonReportTest {

    /** Same election as StvTextReportTest#rendersQuotaRoundsAndElectedCandidates. */
    private static StvResult sampleResult() {
        Candidate x = Candidate.of("X");
        Candidate y = Candidate.of("Y");
        Candidate z = Candidate.of("Z");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(x)), BigInteger.valueOf(5)),
                new Ballot(List.of(Set.of(y), Set.of(x)), BigInteger.valueOf(3)),
                new Ballot(List.of(Set.of(z), Set.of(x)), BigInteger.valueOf(3)));
        StvRuleset ruleset = new StvRuleset(
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(x, y, z)));
        return StvCounter.count(List.of(x, y, z), ballots, 1, ruleset);
    }

    @Test
    void buildsAStructuredTreeMatchingTheTextReportsFigures() {
        StvJsonReport.Report report = StvJsonReport.toReport(sampleResult(), Exact.INSTANCE);

        assertThat(report.quota()).isEqualTo("6");
        assertThat(report.electedInOrder()).containsExactly("X");
        assertThat(report.rounds()).hasSize(2);

        StvJsonReport.Round round1 = report.rounds().get(0);
        assertThat(round1.roundNumber()).isEqualTo(1);
        assertThat(round1.tallies()).containsExactly(
                java.util.Map.entry("X", "5"), java.util.Map.entry("Y", "3"), java.util.Map.entry("Z", "3"));
        assertThat(round1.action().type()).isEqualTo("eliminated");
        assertThat(round1.action().candidate()).isEqualTo("Y");
        assertThat(round1.action().surplusTransferred()).isNull();
        assertThat(round1.exhaustedVotes()).isEqualTo("0");

        StvJsonReport.Round round2 = report.rounds().get(1);
        assertThat(round2.tallies()).containsExactly(
                java.util.Map.entry("X", "8"), java.util.Map.entry("Z", "3"));
        assertThat(round2.action().type()).isEqualTo("elected");
        assertThat(round2.action().candidate()).isEqualTo("X");
        assertThat(round2.action().surplusTransferred()).isEqualTo("0"); // the only seat filled: no transfer
    }

    @Test
    void rendersValidJsonWithoutNullFieldsForEliminationRounds() {
        String json = StvJsonReport.render(sampleResult(), Exact.INSTANCE);

        assertThat(json).doesNotContain("surplusTransferred\" : null");
        assertThat(json).contains("\"quota\" : \"6\"");
        assertThat(json).contains("\"type\" : \"eliminated\"");
        assertThat(json).contains("\"electedInOrder\" : [ \"X\" ]");
    }

    @Test
    void refusesTwoCandidatesOfTheSameNameRatherThanDropOnesTally() {
        // Counted directly, not through the Tabulator: the report kept only the second Alex's 3 votes.
        Candidate first = new Candidate("1", "Alex");
        Candidate second = new Candidate("2", "Alex");
        Candidate sam = new Candidate("3", "Sam");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(first)), BigInteger.valueOf(5)),
                new Ballot(List.of(Set.of(second)), BigInteger.valueOf(3)),
                new Ballot(List.of(Set.of(sam)), BigInteger.ONE));
        StvRuleset ruleset = new StvRuleset(DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(first, second, sam)));
        StvResult result = StvCounter.count(List.of(first, second, sam), ballots, 1, ruleset);

        assertThatThrownBy(() -> StvJsonReport.render(result, Exact.INSTANCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("candidates 1 and 2 are both named \"Alex\"");
    }
}
