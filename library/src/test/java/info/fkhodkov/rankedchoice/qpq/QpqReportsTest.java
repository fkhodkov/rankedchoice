package info.fkhodkov.rankedchoice.qpq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;

class QpqReportsTest {

    private static final Candidate A = Candidate.of("A");
    private static final Candidate B = Candidate.of("B");
    private static final Candidate C = Candidate.of("C");

    /** QpqCounterTest's majority-runoff case: B excluded at quota 7.5, then A elected at quota 5.5. */
    private static QpqResult result() {
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(A)), BigInteger.valueOf(6)),
                new Ballot(List.of(Set.of(B)), BigInteger.valueOf(4)),
                new Ballot(List.of(Set.of(C), Set.of(A)), BigInteger.valueOf(5)));
        return QpqCounter.count(List.of(A, B, C), ballots, 1,
                new QpqRuleset(new DeclaredOrderTieBreak(List.of(A, B, C))));
    }

    @Test
    void csvHasOneRowPerCandidatePerStage() {
        assertThat(QpqReports.csv(result())).isEqualTo("""
                stage,restarted,candidate,quotient,quota,action,informal_ballots
                1,true,A,6,7.5,,0
                1,true,C,5,7.5,,0
                1,true,B,4,7.5,excluded,0
                2,true,A,6,5.5,elected,0
                2,true,C,5,5.5,,0
                """);
    }

    @Test
    void jsonAndHtmlCarryTheSameFigures() {
        QpqReports.Report report = QpqReports.toReport(result());
        assertThat(report.stages()).hasSize(2);
        assertThat(report.stages().getFirst().quotients()).isEqualTo(Map.of("A", "6", "C", "5", "B", "4"));
        assertThat(report.stages().getFirst().action()).isEqualTo(new QpqReports.Action("excluded", "B"));
        assertThat(report.electedInOrder()).containsExactly("A");
        assertThat(QpqReports.json(result())).contains("\"quota\" : \"5.5\"");
        assertThat(QpqReports.html(result()))
                .contains("<h2>Stage 2 (restarted)</h2>\n<p>Quota: 5.5</p>")
                .contains("<tr class=\"acted\"><td>A</td><td>6</td></tr>")
                .contains("<p>Elected: A</p>");
    }

    @Test
    void refusesTwoCandidatesOfTheSameNameRatherThanDropOnesQuotient() {
        Candidate first = new Candidate("1", "Alex");
        Candidate second = new Candidate("2", "Alex");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(first)), BigInteger.valueOf(5)),
                new Ballot(List.of(Set.of(second)), BigInteger.valueOf(3)),
                new Ballot(List.of(Set.of(C)), BigInteger.ONE));
        QpqResult result = QpqCounter.count(List.of(first, second, C), ballots, 1,
                new QpqRuleset(new DeclaredOrderTieBreak(List.of(first, second, C))));

        assertThatThrownBy(() -> QpqReports.json(result)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("candidates 1 and 2 are both named \"Alex\"");
    }
}
