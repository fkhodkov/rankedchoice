package info.fkhodkov.rankedchoice.stv.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.config.ConfigLoader;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.config.StvRulesetParser;

/**
 * Six of San Francisco's single-seat contests (see the fixture's README), counted with the {@code irv}
 * ruleset: every official round is one of this count's rounds, in order, with the same candidates
 * continuing and the same votes, to the vote; and the same candidate wins. Since 2019 that is round for
 * round; before, the Department defeated together the candidates who couldn't overtake the next one up,
 * which is several of this count's rounds in one, and stopped at a majority.
 */
class SanFranciscoIrvFixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/sanfrancisco");

    @ParameterizedTest
    @ValueSource(strings = {"2014-supervisor-10", "2015-mayor", "2018-supervisor-10", "2019-mayor",
        "2020-supervisor-7", "2024-supervisor-9"})
    void everyOfficialRoundIsThisCountsRound(String contest) throws IOException {
        ParsedElection election;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(DIRECTORY.resolve(contest + ".blt.gz"))),
                StandardCharsets.UTF_8))) {
            election = BltParser.parse(reader.lines());
        }
        List<Candidate> candidates = election.candidates();
        StvResult result = StvCounter.count(candidates, election.ballots(), 1,
                StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/irv.yaml"), candidates));
        JsonNode official = new ObjectMapper().readTree(DIRECTORY.resolve(contest + ".official.json").toFile());

        List<JsonNode> rounds = new ArrayList<>();
        official.path("rounds").forEach(rounds::add);
        int start = 0;
        for (int k = 0; k < rounds.size(); k++) {
            Map<String, Fraction> tally = new LinkedHashMap<>();
            rounds.get(k).properties().forEach(entry -> {
                if (entry.getValue().asLong() > 0) {
                    tally.put(key(entry.getKey()), Fraction.of(entry.getValue().asLong()));
                }
            });
            int i = start;
            while (i < result.rounds().size() && !continuing(result.rounds().get(i)).keySet().equals(tally.keySet())) {
                i++;
            }
            assertThat(i).as("official round %d's candidates continue in a round of this count", k + 1)
                    .isLessThan(result.rounds().size());
            assertThat(continuing(result.rounds().get(i))).as("official round %d", k + 1).isEqualTo(tally);
            start = i;
        }
        assertThat(result.electedInOrder()).extracting(candidate -> key(candidate.name()))
                .containsExactly(key(official.path("elected").get(0).asText()));
    }

    /** The candidates with votes in a round, by {@link #key}. */
    private static Map<String, Fraction> continuing(RoundRecord round) {
        Map<String, Fraction> tally = new LinkedHashMap<>();
        round.tallies().forEach((candidate, votes) -> {
            if (votes.signum() > 0) {
                tally.put(key(candidate.name()), votes);
            }
        });
        return tally;
    }

    /** A name's letters, so the record's spelling and the report's meet. */
    private static String key(String name) {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD).toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z]", "");
    }
}
