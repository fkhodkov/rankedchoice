package info.fkhodkov.rankedchoice.stv.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Eastpointe, Michigan's City Council elections of 2019 and 2021, from Macomb County's cast vote
 * records, counted with the {@code portland} ruleset (both are RCTab counts) against the County's
 * RCTab summaries (see the fixture's README): every official round is one of this count's rounds,
 * every candidate to 4 decimal places, at the official threshold, and the same two are elected.
 * The last round, after the last surplus moves once both seats are filled, isn't one here; and
 * the 2021 summary's round 4 slip is held to its consistent figure.
 */
class EastpointeFixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/eastpointe");
    private static final Fraction BAKER_AS_PRINTED = Fraction.of(10588175, 10000);
    private static final Fraction BAKER_CONSISTENT = Fraction.of(10568175, 10000);

    @ParameterizedTest
    @ValueSource(strings = {"council-2019", "council-2021"})
    void everyOfficialRoundIsARoundOfThisCount(String contest) throws IOException {
        ParsedElection election = BltParser.parse(
                Files.readAllLines(DIRECTORY.resolve(contest + ".blt"), StandardCharsets.UTF_8).stream());
        List<Candidate> candidates = election.candidates();
        StvResult result = StvCounter.count(candidates, election.ballots(), election.seats(),
                StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/portland.yaml"), candidates));
        JsonNode official = new ObjectMapper().readTree(DIRECTORY.resolve(contest + ".official.json").toFile());

        assertThat(result.quota()).isEqualTo(Fraction.of(official.path("config").path("threshold").asLong()));
        List<JsonNode> rounds = new ArrayList<>();
        official.path("results").forEach(rounds::add);
        assertThat(rounds.getLast().path("tallyResults").isEmpty()).isTrue();
        rounds.removeLast();
        int from = 0;
        for (JsonNode round : rounds) {
            Map<String, Fraction> tally = new LinkedHashMap<>();
            round.path("tally").properties().forEach(entry -> tally.put(entry.getKey(), decimal(entry.getValue())));
            if (contest.equals("council-2021") && round.path("round").asInt() == 4) {
                assertThat(tally.get("Rob Baker")).isEqualTo(BAKER_AS_PRINTED);
                tally.put("Rob Baker", BAKER_CONSISTENT);
            }
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

    /** An official figure, "1673.9009" or "1805.0", exactly. */
    private static Fraction decimal(JsonNode number) {
        BigDecimal value = new BigDecimal(number.asText());
        return value.scale() <= 0 ? Fraction.of(value.toBigIntegerExact())
                : Fraction.of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()));
    }
}
