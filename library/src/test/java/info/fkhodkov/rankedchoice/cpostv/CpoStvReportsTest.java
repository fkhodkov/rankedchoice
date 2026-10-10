package info.fkhodkov.rankedchoice.cpostv;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;

class CpoStvReportsTest {

    private static final Candidate A = Candidate.of("A");
    private static final Candidate B = Candidate.of("B");
    private static final Candidate C = Candidate.of("C");

    /** 2 seats of 3: {A, B} beats {A, C} and {B, C}; quota floor(10/3)+1 = 4. */
    private static CpoStvResult result() {
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(A), Set.of(B)), BigInteger.valueOf(5)),
                new Ballot(List.of(Set.of(B)), BigInteger.valueOf(3)),
                new Ballot(List.of(Set.of(C)), BigInteger.valueOf(2)));
        return CpoStvCounter.count(List.of(A, B, C), ballots, 2,
                new CpoStvRuleset(DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE));
    }

    @Test
    void jsonListsEveryPairwiseScoreAndTheRanking() {
        CpoStvReports.Report report = CpoStvReports.toReport(result());
        assertThat(report.winners()).containsExactly(List.of("A", "B"));
        assertThat(report.ranking().getFirst()).containsExactly(List.of("A", "B"));
        assertThat(report.scores()).hasSize(6); // 3 outcomes, every ordered pair
        assertThat(report.scores()).allSatisfy(score -> assertThat(score.outcome()).isNotEqualTo(score.against()));
        assertThat(CpoStvReports.json(result())).contains("\"winners\" : [ [ \"A\", \"B\" ] ]");
    }

    @Test
    void csvHasOneRowPerOrderedPairAndHtmlShowsTheTable() {
        String csv = CpoStvReports.csv(result());
        assertThat(csv.lines()).hasSize(7).first().isEqualTo("outcome,place,against,score,informal_ballots");
        assertThat(csv.lines().skip(1).filter(line -> line.startsWith("\"A, B\",1,"))).hasSize(2);
        assertThat(CpoStvReports.html(result()))
                .contains("<p>Winning outcome: {A, B}</p>")
                .contains("<h2>Scores</h2>");
    }

    /** As many seats as candidates: one possible outcome, nothing to compare it with. */
    @Test
    void aSingleOutcomeReportsTheWinnerWithNoScores() {
        CpoStvResult result = CpoStvCounter.count(List.of(A, B),
                List.of(new Ballot(List.of(Set.of(A), Set.of(B)), BigInteger.TWO)), 2,
                new CpoStvRuleset(DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE));

        CpoStvReports.Report report = CpoStvReports.toReport(result);
        assertThat(report.winners()).containsExactly(List.of("A", "B"));
        assertThat(report.scores()).isEmpty();
        assertThat(CpoStvReports.csv(result).lines())
                .containsExactly("outcome,place,against,score,informal_ballots", "\"A, B\",1,,,0");
        assertThat(CpoStvReports.html(result)).contains("<p>Winning outcome: {A, B}</p>").doesNotContain("Scores");
    }
}
