package info.fkhodkov.rankedchoice.schulzestv;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

class SchulzeStvReportsTest {

    private static final Candidate A = Candidate.of("A");
    private static final Candidate B = Candidate.of("B");
    private static final Candidate C = Candidate.of("C");
    private static final Candidate D = Candidate.of("D");

    /** {B,D} and {C,D} both go unbeaten; the single-winner ranking picks {C,D}. */
    private static SchulzeStvResult result() {
        return SchulzeStvCounter.count(List.of(A, B, C, D), List.of(
                new Ballot(List.of(Set.of(B), Set.of(C), Set.of(D)), BigInteger.ONE),
                new Ballot(List.of(Set.of(D), Set.of(C), Set.of(B)), BigInteger.ONE),
                new Ballot(List.of(Set.of(D), Set.of(A), Set.of(C), Set.of(B)), BigInteger.ONE)), 2);
    }

    @Test
    void jsonListsTheWinnersPotentialWinnersRankingAndEverySupport() {
        SchulzeStvReports.Report report = SchulzeStvReports.toReport(result());
        assertThat(report.winners()).containsExactly(List.of("C", "D"));
        assertThat(report.potentialWinners()).containsExactlyInAnyOrder(List.of("B", "D"), List.of("C", "D"));
        assertThat(report.supports()).hasSize(12); // 4 contests of 3, one support per member
        assertThat(SchulzeStvReports.json(result())).contains("\"winners\" : [ [ \"C\", \"D\" ] ]");
    }

    @Test
    void csvHasEverySupportThenThePotentialWinners() {
        String csv = SchulzeStvReports.csv(result());
        assertThat(csv.lines()).hasSize(1 + 12 + 2).first().isEqualTo("committee,against,support,potential_winner,winner");
        assertThat(csv.lines()).contains("\"C, D\",,,true,true", "\"B, D\",,,true,false");
    }

    @Test
    void textAndHtmlShowTheTieBreak() {
        assertThat(SchulzeStvTextReport.render(result()))
                .startsWith("Winning committee: {C, D}\n")
                .contains("Potential winners (no committee beats them): ")
                .contains("Tie broken by the single-winner Schulze ranking:");
        assertThat(SchulzeStvReports.html(result()))
                .contains("<p>Winning committee: {C, D}</p>")
                .contains("<h2>Supports</h2>");
    }
}
