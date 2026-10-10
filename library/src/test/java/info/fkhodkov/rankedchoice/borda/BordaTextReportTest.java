package info.fkhodkov.rankedchoice.borda;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

class BordaTextReportTest {

    @Test
    void rendersScoresHighestToLowest() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        List<Ballot> ballots = List.of(new Ballot(List.of(Set.of(b), Set.of(a)), BigInteger.ONE));

        BordaResult result = BordaMethod.count(List.of(a, b), ballots, ClassicBorda.INSTANCE);
        String report = BordaTextReport.render(result);

        assertThat(report).isEqualTo("B: 1\nA: 0\n");
    }
}
