package info.fkhodkov.rankedchoice.schulzestv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * Tideman's election A53, the worked example in section 9.3 of Schulze's paper (see the
 * fixture's README): the list is the paper's, J A G D F B E C I H, the fifth place going to F
 * over B by the single-winner ranking as the paper says, and every comparison behind every place
 * matches the value Schulze's own program publishes, to the 6 decimals it gives.
 */
class SchulzeProportionalTideman53FixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/schulzestv");
    private static final Pattern PLACE = Pattern.compile("The (\\d+)\\. place goes to candidate (\\S+)\\.");

    @Test
    void reproducesThePapersListAndEveryPublishedComparison() throws IOException {
        ParsedElection election = BltParser.parse(DIRECTORY.resolve("tideman-a53.blt"));
        SchulzeProportionalResult result = SchulzeProportionalRanking.count(election.candidates(), election.ballots());

        assertThat(result.ranking()).extracting(Candidate::name)
                .containsExactly("J", "A", "G", "D", "F", "B", "E", "C", "I", "H");
        assertThat(result.places().get(4).potentialWinners()).extracting(Candidate::name)
                .containsExactlyInAnyOrder("B", "F");

        List<String> rows = new ArrayList<>();
        List<String[]> values = new ArrayList<>();
        int checked = 0;
        for (String line : Files.readAllLines(DIRECTORY.resolve("tideman-a53.list.txt"))) {
            Matcher place = PLACE.matcher(line.trim());
            if (place.matches()) {
                int k = Integer.parseInt(place.group(1));
                assertThat(result.ranking().get(k - 1).name()).isEqualTo(place.group(2));
                Map<Candidate, Map<Candidate, Fraction>> comparisons = result.places().get(k - 1).comparisons();
                for (int r = 0; r < rows.size(); r++) {
                    for (int c = 0; c < rows.size(); c++) {
                        if (r == c) {
                            continue;
                        }
                        Fraction ours = comparisons.get(named(election, rows.get(r))).get(named(election, rows.get(c)));
                        assertThat(ours.toBigDecimal(6, RoundingMode.HALF_UP))
                                .as("place %d: H[%s][%s]", k, rows.get(r), rows.get(c))
                                .isEqualTo(new BigDecimal(values.get(r)[c]));
                        checked++;
                    }
                }
                rows.clear();
                values.clear();
            } else if (!line.isBlank()) {
                String[] fields = line.trim().split("\\s+");
                rows.add(fields[0]);
                values.add(java.util.Arrays.copyOfRange(fields, 1, fields.length));
            }
        }
        assertThat(checked).isEqualTo(330);
    }

    /**
     * Given the paper's list, every place is marked given and holds the same comparisons and
     * potential winners; given another first place, that candidate takes it, the method's own
     * potential winner is still reported, and the method fills the rest.
     */
    @Test
    void aGivenListKeepsEveryPlacesComparisons() throws IOException {
        ParsedElection election = BltParser.parse(DIRECTORY.resolve("tideman-a53.blt"));
        SchulzeProportionalResult plain = SchulzeProportionalRanking.count(election.candidates(), election.ballots());

        SchulzeProportionalResult followed = SchulzeProportionalRanking.count(election.candidates(),
                election.ballots(), plain.ranking());
        assertThat(followed.ranking()).isEqualTo(plain.ranking());
        for (int k = 0; k < plain.places().size(); k++) {
            SchulzeProportionalResult.Place place = followed.places().get(k);
            assertThat(place.given()).isTrue();
            assertThat(place.comparisons()).isEqualTo(plain.places().get(k).comparisons());
            assertThat(place.potentialWinners()).isEqualTo(plain.places().get(k).potentialWinners());
        }

        SchulzeProportionalResult otherFirst = SchulzeProportionalRanking.count(election.candidates(),
                election.ballots(), List.of(named(election, "H")));
        assertThat(otherFirst.ranking().getFirst().name()).isEqualTo("H");
        assertThat(otherFirst.places().getFirst().potentialWinners()).extracting(Candidate::name).containsExactly("J");
        assertThat(otherFirst.places().getFirst().comparisons()).isEqualTo(plain.places().getFirst().comparisons());
        assertThat(otherFirst.places().get(1).given()).isFalse();
    }

    private static Candidate named(ParsedElection election, String name) {
        return election.candidates().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow();
    }
}
