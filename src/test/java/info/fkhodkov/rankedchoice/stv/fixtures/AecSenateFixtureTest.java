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
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
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
 * Tasmania's 2019 Senate election, from the AEC's formal preference data, counted with the
 * {@code senate} ruleset against the AEC's official distribution of preferences (see the
 * fixture's README): every round of this count is one of the official counts the file gives,
 * every candidate's votes, the exhausted votes and the loss by fraction, to the vote; and the
 * same six senators are elected.
 *
 * <p>That election never transfers two surpluses that differ in order of election and size, so
 * a synthetic election counted by pyRCV2 covers that: in s219 surpluses go in order of election
 * rather than largest first. (See tools/reference/pyrcv2 for the 200 random elections it comes
 * from, and tools/reference/aec for every state's count in 2019 and 2022.)
 */
class AecSenateFixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/aec");

    private record Count(int number, Map<String, Fraction> votes, Fraction exhausted, Fraction loss) {
    }

    @Test
    void everyRoundIsAnOfficialCount() throws IOException {
        ParsedElection election;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(DIRECTORY.resolve("tas-2019-senate.blt.gz"))),
                StandardCharsets.UTF_8))) {
            election = BltParser.parse(reader.lines());
        }
        List<Candidate> candidates = election.candidates();
        StvResult result = StvCounter.count(candidates, election.ballots(), election.seats(),
                StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/senate.yaml"), candidates));
        List<Count> official = read(DIRECTORY.resolve("tas-2019-senate.official.csv"));

        assertThat(result.quota()).isEqualTo(Fraction.of(50285)); // 351,988 / 7, plus one
        int from = 0;
        for (RoundRecord round : result.rounds()) {
            Integer match = null;
            for (int i = from; i < official.size() && match == null; i++) {
                if (matches(round, official.get(i))) {
                    match = i;
                }
            }
            assertThat(match).as("round %d is an official count", round.roundNumber()).isNotNull();
            from = match;
        }
        assertThat(result.electedInOrder()).extracting(Candidate::name).containsExactly(
                "COLBECK Richard", "BROWN Carol", "CHANDLER Claire", "McKIM Nick", "BILYK Catryna", "LAMBIE Jacqui");
    }

    @ParameterizedTest
    @ValueSource(strings = {"s219"})
    void everyRoundMatchesPyRcv2(String name) throws IOException {
        ParsedElection election = BltParser.parse(DIRECTORY.resolve("pyrcv2/" + name + ".blt"));
        StvResult count = StvCounter.count(election.candidates(), election.ballots(), election.seats(),
                StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/senate.yaml"), election.candidates()));

        assertThat(CountSheet.read(DIRECTORY.resolve("pyrcv2/" + name + ".stages.txt")).match(count))
                .doesNotContainNull();
    }

    private static boolean matches(RoundRecord round, Count count) {
        for (Map.Entry<Candidate, Fraction> tally : round.tallies().entrySet()) {
            if (!tally.getValue().equals(count.votes().get(tally.getKey().name()))) {
                return false;
            }
        }
        return round.exhaustedVotes().equals(count.exhausted()) && round.roundingLoss().equals(count.loss());
    }

    /** The CSV's columns come in pairs (votes, state); rows are candidates, then exhausted and loss. */
    private static List<Count> read(Path path) throws IOException {
        List<String[]> rows = Files.readAllLines(path).stream().map(line -> line.split(",", -1)).toList();
        List<Count> counts = new ArrayList<>();
        for (int column = 1; column < rows.getFirst().length; column += 2) {
            if (rows.getFirst()[column].isBlank()) {
                continue;
            }
            Map<String, Fraction> votes = new LinkedHashMap<>();
            for (String[] row : rows.subList(2, rows.size() - 2)) {
                votes.put(row[0], Fraction.of(Long.parseLong(row[column])));
            }
            counts.add(new Count(Integer.parseInt(rows.getFirst()[column]), votes,
                    Fraction.of(Long.parseLong(rows.get(rows.size() - 2)[column])),
                    Fraction.of(Long.parseLong(rows.get(rows.size() - 1)[column]))));
        }
        return counts;
    }
}
