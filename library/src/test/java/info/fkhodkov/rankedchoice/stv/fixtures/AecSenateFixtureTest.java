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
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
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
 * Tasmania's 2016 and 2019 Senate elections, from the AEC's formal preference data, counted with
 * the {@code senate} ruleset against the AEC's official distributions of preferences (see the
 * fixtures' README): every round of each count is one of the official counts, every candidate's
 * votes, the exhausted votes and the loss by fraction, to the vote; and the same senators are
 * elected in the same order. 2016 elected 12 (a double dissolution) and ends on s273(17): with
 * one seat left between McKim and McCulloch, McKim is elected on 21,247 votes against 21,106,
 * below the quota, without McCulloch being excluded.
 *
 * <p>That election never transfers two surpluses that differ in order of election and size, so
 * a synthetic election counted by pyRCV2 covers that: in r134 surpluses go in order of election
 * rather than largest first, C2's 20 votes before C5's 23. (See tools/reference/pyrcv2 for the
 * 200 random elections it comes from, and tools/reference/aec for every state's count in 2019
 * and 2022.)
 */
class AecSenateFixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/aec");

    private record Count(int number, Map<String, Fraction> votes, Fraction exhausted, Fraction loss) {
    }

    @Test
    void everyRoundIsAnOfficialCount() throws IOException {
        StvResult result = count("tas-2019-senate.blt.gz");

        assertThat(result.quota()).isEqualTo(Fraction.of(50285)); // 351,988 / 7, plus one
        assertEveryRoundIsOneOf(result, read(DIRECTORY.resolve("tas-2019-senate.official.csv")));
        assertThat(result.electedInOrder()).extracting(Candidate::name).containsExactly(
                "COLBECK Richard", "BROWN Carol", "CHANDLER Claire", "McKIM Nick", "BILYK Catryna", "LAMBIE Jacqui");
    }

    @Test
    void everyRoundOf2016IsAnOfficialCount() throws IOException {
        StvResult result = count("tas-2016-senate.blt.gz");
        List<String> officiallyElected = new ArrayList<>();
        List<Count> official = readDop(DIRECTORY.resolve("tas-2016-senate.dop.csv.gz"), officiallyElected);

        assertThat(result.quota()).isEqualTo(Fraction.of(26090)); // 339,159 / 13, plus one
        assertEveryRoundIsOneOf(result, official);
        assertThat(result.electedInOrder()).extracting(Candidate::name).containsExactlyElementsOf(officiallyElected);
        RoundRecord last = result.rounds().getLast();
        assertThat(last.action()).isEqualTo(new RoundAction.Elected(candidate(result, "McKIM Nick"), Fraction.ZERO));
        assertThat(last.tallies()).containsValues(Fraction.of(21247), Fraction.of(21106)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"r134"})
    void everyRoundMatchesPyRcv2(String name) throws IOException {
        ParsedElection election = BltParser.parse(DIRECTORY.resolve("pyrcv2/" + name + ".blt"));
        StvResult count = StvCounter.count(election.candidates(), election.ballots(), election.seats(),
                StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/senate.yaml"), election.candidates()));

        assertThat(CountSheet.read(DIRECTORY.resolve("pyrcv2/" + name + ".stages.txt")).match(count))
                .doesNotContainNull();
    }

    private static StvResult count(String file) throws IOException {
        ParsedElection election;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(DIRECTORY.resolve(file))), StandardCharsets.UTF_8))) {
            election = BltParser.parse(reader.lines());
        }
        List<Candidate> candidates = election.candidates();
        return StvCounter.count(candidates, election.ballots(), election.seats(),
                StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/senate.yaml"), candidates));
    }

    private static Candidate candidate(StvResult result, String name) {
        return result.rounds().getFirst().tallies().keySet().stream()
                .filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
    }

    /** Each round matches an official count, in order: the same one, or a later one. */
    private static void assertEveryRoundIsOneOf(StvResult result, List<Count> official) {
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

    /**
     * The AEC's own distribution of preferences (SenateStateDOPDownload), less its comments: a
     * row per candidate per count, then "Exhausted" and "Gain/Loss" rows, whose progressive
     * total is the loss by fraction. Fills {@code elected} with the senators in order.
     */
    private static List<Count> readDop(Path path, List<String> elected) throws IOException {
        Map<Integer, Count> counts = new LinkedHashMap<>();
        Map<Integer, String> order = new TreeMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
            for (String line : reader.lines().skip(1).toList()) {
                String[] row = line.split(",", -1);
                int number = Integer.parseInt(row[4]);
                Fraction total = Fraction.of(Long.parseLong(row[11]));
                Count count = counts.computeIfAbsent(number,
                        n -> new Count(n, new LinkedHashMap<>(), Fraction.ZERO, Fraction.ZERO));
                switch (row[7]) {
                    case "Exhausted" -> counts.put(number, new Count(number, count.votes(), total, count.loss()));
                    case "Gain/Loss" -> counts.put(number, new Count(number, count.votes(), count.exhausted(), total));
                    default -> {
                        String name = row[7] + " " + row[8];
                        count.votes().put(name, total);
                        if (!row[15].isEmpty() && !row[15].equals("0")) {
                            order.put(Integer.parseInt(row[15]), name);
                        }
                    }
                }
            }
        }
        elected.addAll(order.values());
        return new ArrayList<>(counts.values());
    }
}
