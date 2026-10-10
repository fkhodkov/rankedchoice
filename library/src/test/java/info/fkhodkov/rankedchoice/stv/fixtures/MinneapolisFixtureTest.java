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
 * Minneapolis's multi-seat contests of 2017, 2021 and 2025, from the City's cast vote records,
 * counted with the {@code minneapolis} ruleset, and its 2009 Park Board contest, from FairVote's
 * copy of the record, counted with the 2006 ordinance's rules alone (minneapolis-2009.yaml),
 * and two single-seat races, the 2021 mayoral race (which turns on the rankings test) and Ward 6
 * of 2023 (three candidates defeated together), with {@code minneapolis}, against the City's
 * summary statements (see the
 * fixture's README): every official round is one of this count's rounds, every candidate's votes
 * to the 4 decimal places of the official figures, at the official threshold, and the same
 * candidates are elected.
 */
class MinneapolisFixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/minneapolis");

    @ParameterizedTest
    @ValueSource(strings = {"park-2009", "park-2017", "park-2021", "park-2025", "bet-2017", "bet-2021", "bet-2025",
        "mayor-2021", "ward-6-2023"})
    void everyOfficialRoundIsARoundOfThisCount(String contest) throws IOException {
        ParsedElection election = BltParser.parse(
                Files.readAllLines(DIRECTORY.resolve(contest + ".blt"), StandardCharsets.UTF_8).stream());
        List<Candidate> candidates = election.candidates();
        var config = contest.endsWith("2009")
                ? ConfigLoader.load(DIRECTORY.resolve("minneapolis-2009.yaml"))
                : ConfigLoader.loadResource("/rulesets/minneapolis.yaml");
        StvResult result = StvCounter.count(candidates, election.ballots(), election.seats(),
                StvRulesetParser.parse(config, candidates));
        JsonNode official = new ObjectMapper().readTree(DIRECTORY.resolve(contest + ".official.json").toFile());

        assertThat(result.quota()).isEqualTo(Fraction.of(official.path("threshold").asLong()));
        int from = 0;
        int number = 0;
        for (JsonNode round : official.path("rounds")) {
            number++;
            Map<String, Fraction> tally = new LinkedHashMap<>();
            round.properties().forEach(entry -> tally.put(entry.getKey(), decimal(entry.getValue())));
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
        official.path("elected").forEach(name -> elected.add(name.asText()));
        assertThat(result.electedInOrder()).extracting(Candidate::name).containsExactlyInAnyOrderElementsOf(elected);
    }

    private static boolean matches(RoundRecord round, Map<String, Fraction> tally) {
        for (Map.Entry<Candidate, Fraction> votes : round.tallies().entrySet()) {
            // FairVote's 2009 record lumps every write-in as one; the City's summary lists each.
            if (votes.getKey().name().equals("Write-ins")) {
                continue;
            }
            if (!votes.getValue().equals(tally.get(votes.getKey().name()))) {
                return false;
            }
        }
        return true;
    }

    /** An official figure, "7494.0000", exactly. */
    private static Fraction decimal(JsonNode text) {
        BigDecimal value = new BigDecimal(text.asText());
        return Fraction.of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()));
    }
}
