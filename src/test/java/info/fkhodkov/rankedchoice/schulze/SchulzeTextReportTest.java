package info.fkhodkov.rankedchoice.schulze;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

class SchulzeTextReportTest {

    @Test
    void rendersMatricesAndRanking() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(a), Set.of(b)), BigInteger.valueOf(7)),
                new Ballot(List.of(Set.of(b), Set.of(a)), BigInteger.valueOf(3)));

        SchulzeResult result = SchulzeMethod.count(List.of(a, b), ballots);
        String report = SchulzeTextReport.render(List.of(a, b), result);

        assertThat(report).contains("Pairwise preferences (d):");
        assertThat(report).contains("A: - 7");
        assertThat(report).contains("B: 3 -");
        assertThat(report).contains("Strongest paths (p):");
        assertThat(report).contains("Ranking:");
        assertThat(report).contains("1. A");
        assertThat(report).contains("2. B");
    }
}
