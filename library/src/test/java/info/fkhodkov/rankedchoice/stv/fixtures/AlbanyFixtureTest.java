package info.fkhodkov.rankedchoice.stv.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Albany, California's City Council and Board of Education contests of 2022 and 2024, from
 * Alameda County's cast vote records, counted with the {@code albany} ruleset (2024) or the
 * County's 2022 settings against its RCV Detailed Reports (see the fixture's README): every
 * official round is one of this count's rounds, every candidate's votes to the report's decimal
 * places, at the official first threshold, and the same candidates are elected.
 */
class AlbanyFixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/albany");

    @ParameterizedTest
    @ValueSource(strings = {"council-2022", "school-2022", "council-2024", "school-2024"})
    void everyOfficialRoundIsARoundOfThisCount(String contest) throws IOException {
        ParsedElection election = BltParser.parse(
                Files.readAllLines(DIRECTORY.resolve(contest + ".blt"), StandardCharsets.UTF_8).stream());
        List<Candidate> candidates = election.candidates();
        var config = contest.endsWith("2022")
                ? ConfigLoader.load(DIRECTORY.resolve("albany-2022.yaml"))
                : ConfigLoader.loadResource("/rulesets/albany.yaml");
        StvResult result = StvCounter.count(candidates, election.ballots(), election.seats(),
                StvRulesetParser.parse(config, candidates));
        JsonNode official = new ObjectMapper().readTree(DIRECTORY.resolve(contest + ".official.json").toFile());

        assertThat(result.quota()).isEqualTo(decimal(official.path("thresholds").get(0)));
        int from = 0;
        int number = 0;
        for (JsonNode round : official.path("rounds")) {
            number++;
            Map<String, Fraction> tally = new LinkedHashMap<>();
            round.properties().forEach(entry -> tally.put(key(entry.getKey()), decimal(entry.getValue())));
            Integer match = null;
            for (int i = from; i < result.rounds().size() && match == null; i++) {
                if (matches(result.rounds().get(i), tally)) {
                    match = i;
                }
            }
            assertThat(match).as("official round %d is a round of this count", number).isNotNull();
            from = match;
        }
        List<String> elected = new ArrayList<>();
        official.path("elected").forEach(name -> elected.add(key(name.asText())));
        assertThat(result.electedInOrder()).extracting(candidate -> key(candidate.name()))
                .containsExactlyInAnyOrderElementsOf(elected);
    }

    private static boolean matches(RoundRecord round, Map<String, Fraction> tally) {
        for (Map.Entry<Candidate, Fraction> votes : round.tallies().entrySet()) {
            if (!votes.getValue().equals(tally.get(key(votes.getKey().name())))) {
                return false;
            }
        }
        return true;
    }

    /** A name's letters, so the cast vote record's spelling and the report's meet. */
    private static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKD).toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
    }

    /** An official figure, "2260.958234", exactly. */
    private static Fraction decimal(JsonNode text) {
        BigDecimal value = new BigDecimal(text.asText());
        return Fraction.of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()));
    }
}
