package info.fkhodkov.rankedchoice.borda;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

class BordaReportsTest {

    @Test
    void equalScoresSharePlaceAndEveryFormatAgrees() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        BordaResult result = new BordaResult(
                Map.of(a, Fraction.of(7, 2), b, Fraction.of(7, 2), c, Fraction.of(1)), List.of(a, b, c));

        assertThat(BordaReports.csv(result)).isEqualTo("""
                place,candidate,score
                1,A,3.5
                1,B,3.5
                3,C,1
                """);
        assertThat(BordaReports.toReport(result).ranking().get(1)).isEqualTo(new BordaReports.Entry(1, "B", "3.5"));
        assertThat(BordaReports.html(result)).contains("<tr><td>3</td><td>C</td><td>1</td></tr>");
    }
}
