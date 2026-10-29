package info.fkhodkov.rankedchoice.schulze;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

class SchulzeReportsTest {

    private static final Candidate A = Candidate.of("A");
    private static final Candidate B = Candidate.of("B");
    private static final List<Candidate> CANDIDATES = List.of(A, B);
    private static final SchulzeResult RESULT = SchulzeMethod.count(CANDIDATES, List.of(
            new Ballot(List.of(Set.of(A), Set.of(B)), BigInteger.valueOf(3)),
            new Ballot(List.of(Set.of(B), Set.of(A)), BigInteger.valueOf(2))));

    @Test
    void everyFormatCarriesBothMatricesAndTheRanking() {
        assertThat(SchulzeReports.csv(CANDIDATES, RESULT)).isEqualTo("""
                candidate,place,against,pairwise,strongest_path
                A,1,B,3,3
                B,2,A,2,0
                """);
        SchulzeReports.Report report = SchulzeReports.toReport(CANDIDATES, RESULT);
        assertThat(report.pairwise()).isEqualTo(
                Map.of("A", Map.of("B", BigInteger.valueOf(3)), "B", Map.of("A", BigInteger.valueOf(2))));
        assertThat(report.ranking()).containsExactly(List.of("A"), List.of("B"));
        assertThat(SchulzeReports.html(CANDIDATES, RESULT))
                .contains("<li value=\"1\">A</li>")
                .contains("<h2>Strongest paths (p)</h2>");
    }

    @Test
    void refusesTwoCandidatesOfTheSameNameRatherThanMergeTheirRows() {
        Candidate first = new Candidate("1", "Alex");
        Candidate second = new Candidate("2", "Alex");
        List<Candidate> candidates = List.of(first, second);
        SchulzeResult result = SchulzeMethod.count(candidates, List.of(
                new Ballot(List.of(Set.of(first), Set.of(second)), BigInteger.valueOf(3))));

        assertThatThrownBy(() -> SchulzeReports.json(candidates, result)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("candidates 1 and 2 are both named \"Alex\"");
    }
}
