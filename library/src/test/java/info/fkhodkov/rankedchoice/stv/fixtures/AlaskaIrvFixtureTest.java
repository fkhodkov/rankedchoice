package info.fkhodkov.rankedchoice.stv.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.config.StvRulesetParser;

/**
 * Two of Alaska's House contests (see the fixture's README), counted with the {@code irv} ruleset
 * against the Division of Elections' own reports: every official round's votes are this count's,
 * round for round, to the vote; the candidates are eliminated in the same order; and the same
 * candidate wins.
 */
class AlaskaIrvFixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/alaska");

    @ParameterizedTest
    @ValueSource(strings = {"2022-house-28", "2024-house-38"})
    void everyOfficialRoundIsThisCountsRound(String contest) throws IOException {
        ParsedElection election;
        try (BufferedReader reader = Files.newBufferedReader(DIRECTORY.resolve(contest + ".blt"),
                StandardCharsets.UTF_8)) {
            election = BltParser.parse(reader.lines());
        }
        List<Candidate> candidates = election.candidates();
        StvResult result = StvCounter.count(candidates, election.ballots(), 1,
                StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/irv.yaml"), candidates));
        JsonNode official = new ObjectMapper().readTree(DIRECTORY.resolve(contest + ".official.json").toFile());

        List<JsonNode> rounds = new ArrayList<>();
        official.path("rounds").forEach(rounds::add);
        for (int k = 0; k < rounds.size(); k++) {
            Map<String, Fraction> tally = new LinkedHashMap<>();
            rounds.get(k).properties().forEach(entry -> {
                if (entry.getValue().asLong() > 0) {
                    tally.put(key(entry.getKey()), Fraction.of(entry.getValue().asLong()));
                }
            });
            Map<String, Fraction> ours = new LinkedHashMap<>();
            result.rounds().get(k).tallies().forEach((candidate, votes) -> ours.put(key(candidate.name()), votes));
            assertThat(ours).as("official round %d", k + 1).isEqualTo(tally);
        }
        List<String> eliminated = new ArrayList<>();
        official.path("eliminated").forEach(name -> eliminated.add(key(name.asText())));
        assertThat(result.rounds().stream().map(RoundRecord::action)
                .filter(action -> action instanceof RoundAction.Eliminated)
                .map(action -> key(((RoundAction.Eliminated) action).candidate().name()))
                .limit(eliminated.size()).toList())
                .isEqualTo(eliminated);
        assertThat(result.electedInOrder()).extracting(candidate -> key(candidate.name()))
                .containsExactly(key(official.path("elected").get(0).asText()));
    }

    /** A name's letters, so the record's spelling and the report's meet. */
    private static String key(String name) {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD).toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z]", "");
    }
}
