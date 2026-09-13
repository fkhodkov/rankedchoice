package info.fkhodkov.rankedchoice.borda;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * Reproduces the real 2019 Nauruan parliamentary election's Anabar constituency result under
 * the Dowdall system -- see the citation comment in {@code
 * testdata/borda/nauru-2019-anabar.blt} for where the official per-candidate, per-rank figures
 * came from and why a synthetic (but mathematically guaranteed faithful) ballot reconstruction
 * is used in place of the real, non-public ballots.
 */
class BordaNauru2019AnabarFixtureTest {

    @Test
    void reproducesThe2019AnabarDowdallScoresExactly() {
        ParsedElection election = BltParser.parse(Path.of("src/test/resources/testdata/borda/nauru-2019-anabar.blt"));
        List<Candidate> candidates = election.candidates();

        BordaResult result = BordaMethod.count(candidates, election.ballots(), DowdallScoring.INSTANCE);

        Candidate eoe = candidateNamed(candidates, "Maverick Eoe");
        Candidate deiye = candidateNamed(candidates, "Pyon Deiye");
        Candidate scotty = candidateNamed(candidates, "Ludwig Scotty");
        Candidate akua = candidateNamed(candidates, "Riddell Akua");
        Candidate bop = candidateNamed(candidates, "Jeb Bop");
        Candidate duburiya = candidateNamed(candidates, "Transom Duburiya");

        assertThat(asDecimal(result.scores().get(eoe))).isEqualTo("286.117");
        assertThat(asDecimal(result.scores().get(deiye))).isEqualTo("276.267");
        assertThat(asDecimal(result.scores().get(scotty))).isEqualTo("216.8");
        assertThat(asDecimal(result.scores().get(akua))).isEqualTo("209");
        assertThat(asDecimal(result.scores().get(bop))).isEqualTo("193.2");
        assertThat(asDecimal(result.scores().get(duburiya))).isEqualTo("173.467");

        // Anabar elects 2 seats -- the top 2 by Dowdall score are the real winners.
        assertThat(result.rankingBestToWorst().subList(0, 2)).containsExactly(eoe, deiye);
    }

    private static String asDecimal(Fraction score) {
        return score.round(3, java.math.RoundingMode.HALF_UP).toDecimalString();
    }

    private static Candidate candidateNamed(List<Candidate> candidates, String name) {
        return candidates.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No candidate named " + name));
    }
}
