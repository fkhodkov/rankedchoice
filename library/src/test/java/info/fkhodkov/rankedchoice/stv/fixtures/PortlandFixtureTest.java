package info.fkhodkov.rankedchoice.stv.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
 * Portland's 2024 City Council District 4 contest, from Multnomah County's cast vote record,
 * counted with the {@code portland} ruleset against the County's official round-by-round
 * tabulation (see the fixture's README): every official round is one of this count's rounds,
 * every candidate's votes to the 4 decimal places of the official figures, at the official
 * threshold of 19,180, and the same three councillors are elected. The County's last round, after
 * the last seats are filled, shows a surplus this count doesn't transfer, so it's left out.
 */
class PortlandFixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/portland");

    @Test
    void everyOfficialRoundIsARoundOfThisCount() throws IOException {
        ParsedElection election;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(DIRECTORY.resolve("district-4-2024.blt.gz"))),
                StandardCharsets.UTF_8))) {
            election = BltParser.parse(reader.lines());
        }
        List<Candidate> candidates = election.candidates();
        StvResult result = StvCounter.count(candidates, election.ballots(), election.seats(),
                StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/portland.yaml"), candidates));
        JsonNode official = new ObjectMapper().readTree(DIRECTORY.resolve("district-4-2024.official.json").toFile());

        assertThat(result.quota()).isEqualTo(Fraction.of(official.path("config").path("threshold").asLong()));
        List<JsonNode> rounds = new ArrayList<>();
        official.path("results").forEach(rounds::add);
        if (rounds.getLast().path("tallyResults").isEmpty()) {
            rounds.removeLast();
        }
        int from = 0;
        for (JsonNode round : rounds) {
            Map<String, Fraction> tally = new LinkedHashMap<>();
            round.path("tally").properties().forEach(entry -> tally.put(entry.getKey(), decimal(entry.getValue())));
            Integer match = null;
            for (int i = from; i < result.rounds().size() && match == null; i++) {
                if (matches(result.rounds().get(i), tally)) {
                    match = i;
                }
            }
            assertThat(match).as("official round %d is a round of this count", round.path("round").asInt()).isNotNull();
            from = match;
        }
        List<String> elected = new ArrayList<>();
        official.path("results").forEach(round -> round.path("tallyResults").forEach(action -> {
            if (action.has("elected")) {
                elected.add(action.path("elected").asText());
            }
        }));
        assertThat(result.electedInOrder()).extracting(Candidate::name).containsExactlyInAnyOrderElementsOf(elected);
    }

    private static boolean matches(RoundRecord round, Map<String, Fraction> tally) {
        for (Map.Entry<Candidate, Fraction> votes : round.tallies().entrySet()) {
            if (!votes.getValue().equals(tally.get(votes.getKey().name()))) {
                return false;
            }
        }
        return true;
    }

    /** An official figure, "7893.2520" or "10718", exactly. */
    private static Fraction decimal(JsonNode text) {
        BigDecimal value = new BigDecimal(text.asText());
        return Fraction.of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()));
    }
}
