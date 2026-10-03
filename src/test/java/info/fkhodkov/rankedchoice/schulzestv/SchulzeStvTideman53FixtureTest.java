package info.fkhodkov.rankedchoice.schulzestv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * Tideman's election A53, the worked example in section 8.2 of Schulze's paper (see the
 * fixture's README): every one of the 1,260 supports matches the value Schulze's own program
 * publishes, to the 6 decimals it gives, and the count elects {a, d, g, j}, as the paper does.
 */
class SchulzeStvTideman53FixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/schulzestv");

    @Test
    void reproducesEveryPublishedSupportAndThePapersWinner() throws IOException {
        ParsedElection election = BltParser.parse(DIRECTORY.resolve("tideman-a53.blt"));
        SchulzeStvResult result = SchulzeStvCounter.count(election.candidates(), election.ballots(), election.seats());

        Map<String, Fraction> supports = new HashMap<>();
        for (SchulzeStvResult.Support support : result.supports()) {
            supports.put(key(support.committee().stream().map(Candidate::name).toList(), support.against().name()),
                    support.support());
        }
        List<String> lines = Files.readAllLines(DIRECTORY.resolve("tideman-a53.supports.txt"));
        int checked = 0;
        for (String line : lines) {
            String[] fields = line.trim().split("\\s+");
            List<String> contest = Arrays.asList(fields).subList(1, 6);
            for (int k = 0; k < 5; k++) {
                String against = contest.get(k);
                List<String> committee = contest.stream().filter(name -> !name.equals(against)).toList();
                BigDecimal published = new BigDecimal(fields[6 + k]);
                assertThat(supports.get(key(committee, against)).toBigDecimal(6, RoundingMode.HALF_UP))
                        .as("N[%s; %s]", committee, against)
                        .isEqualTo(published);
                checked++;
            }
        }
        assertThat(checked).isEqualTo(1260).isEqualTo(result.supports().size());

        // Section 8.2.3's example links.
        assertThat(supports.get(key(List.of("A", "B", "G", "J"), "D")).toBigDecimal(6, RoundingMode.HALF_UP))
                .isEqualTo(new BigDecimal("101.411379"));
        assertThat(supports.get(key(List.of("A", "D", "G", "J"), "B")).toBigDecimal(6, RoundingMode.HALF_UP))
                .isEqualTo(new BigDecimal("102.166302"));

        Set<Candidate> adgj = committee(election.candidates(), "A", "D", "G", "J");
        assertThat(result.potentialWinners()).containsExactly(adgj);
        assertThat(result.winners()).containsExactly(adgj);
    }

    private static String key(List<String> committee, String against) {
        return committee.stream().sorted().collect(Collectors.joining(",")) + ";" + against;
    }

    private static Set<Candidate> committee(List<Candidate> candidates, String... names) {
        Set<String> wanted = Set.of(names);
        return Set.copyOf(candidates.stream().filter(candidate -> wanted.contains(candidate.name())).toList());
    }
}
