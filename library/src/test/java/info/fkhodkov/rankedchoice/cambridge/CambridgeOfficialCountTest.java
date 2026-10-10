package info.fkhodkov.rankedchoice.cambridge;

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
import java.util.Map;
import java.util.zip.GZIPInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;

/**
 * Two of Cambridge's official counts, from the City's ballots in the City's order (see the
 * fixture's README): every official count's votes are one of this count's rounds, in order, for
 * every candidate but the write-ins, at the official quota, and the same candidates are elected.
 * Which ballots a surplus moves depends on their order, so this checks the Cincinnati transfer
 * itself against the real counts, not only the outcome.
 */
class CambridgeOfficialCountTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/cambridge");

    @ParameterizedTest
    @ValueSource(strings = {"council-2017", "school-2013"})
    void everyOfficialCountIsARoundOfThisCount(String contest) throws IOException {
        ParsedElection election;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(DIRECTORY.resolve(contest + ".blt.gz"))),
                StandardCharsets.UTF_8))) {
            election = BltParser.parse(reader.lines());
        }
        StvResult result = CambridgeCounter.count(election.candidates(), election.ballots(), election.seats(),
                new CambridgeRuleset(new DeclaredOrderTieBreak(election.candidates())));
        JsonNode official = new ObjectMapper().readTree(DIRECTORY.resolve(contest + ".official.json").toFile());

        assertThat(result.quota()).isEqualTo(Fraction.of(official.path("quota").asLong()));
        Map<String, List<Long>> counts = new LinkedHashMap<>();
        official.path("counts").properties().forEach(entry -> {
            List<Long> votes = new ArrayList<>();
            entry.getValue().forEach(value -> votes.add(value.asLong()));
            counts.put(entry.getKey(), votes);
        });
        int total = counts.values().stream().mapToInt(List::size).max().orElseThrow();
        int from = 0;
        for (int k = 0; k < total; k++) {
            Integer match = null;
            for (int i = from; i < result.rounds().size() && match == null; i++) {
                if (matches(result.rounds().get(i), counts, k)) {
                    match = i;
                }
            }
            assertThat(match).as("official count %d is a round of this count", k + 1).isNotNull();
            from = match;
        }
        List<String> elected = new ArrayList<>();
        official.path("elected").forEach(name -> elected.add(name.asText()));
        assertThat(result.electedInOrder()).extracting(Candidate::name).containsExactlyInAnyOrderElementsOf(elected);
    }

    /** Whether a round's votes are the official count's, for every candidate the sheet still shows. */
    private static boolean matches(RoundRecord round, Map<String, List<Long>> counts, int k) {
        for (Map.Entry<Candidate, Fraction> votes : round.tallies().entrySet()) {
            List<Long> official = counts.get(votes.getKey().name());
            if (official != null && official.size() > k && !votes.getValue().equals(Fraction.of(official.get(k)))) {
                return false;
            }
        }
        return true;
    }
}
