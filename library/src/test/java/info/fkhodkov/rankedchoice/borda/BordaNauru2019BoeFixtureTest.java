package info.fkhodkov.rankedchoice.borda;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * A second, independent real-election cross-check for Dowdall scoring, alongside {@link
 * BordaNauru2019AnabarFixtureTest}: the same 2019 Nauruan election's Boe constituency (5
 * candidates, 2 seats) -- see the citation comment in {@code testdata/borda/nauru-2019-boe.blt}
 * for the official figures and why a synthetic-but-mathematically-faithful ballot
 * reconstruction is used in place of the real, non-public ballots.
 */
class BordaNauru2019BoeFixtureTest {

    @Test
    void reproducesThe2019BoeDowdallScoresExactly() {
        ParsedElection election = BltParser.parse(Path.of("src/test/resources/testdata/borda/nauru-2019-boe.blt"));
        List<Candidate> candidates = election.candidates();

        BordaResult result = BordaMethod.count(candidates, election.ballots(), DowdallScoring.INSTANCE);

        Candidate hunt = candidateNamed(candidates, "Martin Hunt");
        Candidate appi = candidateNamed(candidates, "Asterio Appi");
        Candidate waqa = candidateNamed(candidates, "Baron Waqa");
        Candidate batsiua = candidateNamed(candidates, "Mathew Batsiua");
        Candidate satto = candidateNamed(candidates, "Brett Satto");

        assertThat(asDecimal(result.scores().get(hunt))).isEqualTo("448.167");
        assertThat(asDecimal(result.scores().get(appi))).isEqualTo("413.333");
        assertThat(asDecimal(result.scores().get(waqa))).isEqualTo("350.633");
        assertThat(asDecimal(result.scores().get(batsiua))).isEqualTo("343.6");
        assertThat(asDecimal(result.scores().get(satto))).isEqualTo("296.05");

        // Boe elects 2 seats -- the top 2 by Dowdall score are the real winners.
        assertThat(result.rankingBestToWorst().subList(0, 2)).containsExactly(hunt, appi);
    }

    private static String asDecimal(Fraction score) {
        return score.round(3, RoundingMode.HALF_UP).toDecimalString();
    }

    private static Candidate candidateNamed(List<Candidate> candidates, String name) {
        return candidates.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No candidate named " + name));
    }
}
