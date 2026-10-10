package info.fkhodkov.rankedchoice.stv.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
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
 * NSW council elections of 2024, from the NSW Electoral Commission's ballot papers, counted with
 * the {@code nsw-lg} ruleset against its official distributions of preferences (see the fixtures'
 * README): every round is one of the official counts, every candidate's votes and the votes lost
 * (exhausted plus loss by fraction) to the vote, and the same councillors are elected. Picked for
 * what they exercise:
 *
 * <ul>
 *   <li>Newcastle ward 4: the last seat goes to Richardson, whom Kelso can't overtake even with
 *       Pull's 64-vote surplus, which is never transferred; and exclusions rounded per parcel.
 *   <li>Ballina B ward: Bailey's 440-vote surplus could change the last seat, so it is transferred
 *       first, and Ramsey overtakes Broadley.
 *   <li>Blayney: Dorsett is elected on 568, under the quota of 569, since the two others left have
 *       554 between them.
 *   <li>Coffs Harbour: Williams, elected mayor, leaves the councillor count; her papers go to their
 *       next preference, and the 7 that rank no one else exhaust and don't count towards the quota.
 *   <li>Upper Lachlan and Wentworth: two candidates reach quota on equal votes in one transfer,
 *       and their surpluses go in the order the count before that sets.
 * </ul>
 */
class NswLocalGovernmentFixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/nsw");

    private record Count(String number, Map<String, Fraction> votes, Fraction lost) {
    }

    @ParameterizedTest
    @ValueSource(strings = {"newcastle_ward-4", "ballina_b-ward", "blayney", "coffs-harbour", "upper-lachlan",
        "wentworth"})
    void everyRoundIsAnOfficialCount(String name) throws IOException {
        ParsedElection election;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(DIRECTORY.resolve(name + ".blt.gz"))),
                StandardCharsets.UTF_8))) {
            election = BltParser.parse(reader.lines());
        }
        List<Candidate> candidates = election.candidates().stream()
                .filter(candidate -> !election.withdrawnCandidates().contains(candidate))
                .toList();
        StvResult result = StvCounter.count(candidates, election.ballots(), election.seats(),
                StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/nsw-lg.yaml"), candidates));
        Set<String> officiallyElected = new HashSet<>();
        List<Count> official = read(DIRECTORY.resolve(name + ".official.csv"), officiallyElected);

        int from = 0;
        for (RoundRecord round : result.rounds()) {
            Integer match = null;
            for (int i = from; i < official.size() && match == null; i++) {
                if (matches(round, official.get(i))) {
                    match = i;
                }
            }
            assertThat(match).as("%s round %d is an official count", name, round.roundNumber()).isNotNull();
            from = match;
        }
        assertThat(result.electedInOrder()).extracting(Candidate::name)
                .containsExactlyInAnyOrderElementsOf(officiallyElected);
    }

    private static boolean matches(RoundRecord round, Count count) {
        for (Map.Entry<Candidate, Fraction> tally : round.tallies().entrySet()) {
            if (!tally.getValue().equals(count.votes().get(tally.getKey().name()))) {
                return false;
            }
        }
        return round.exhaustedVotes().add(round.roundingLoss()).equals(count.lost());
    }

    /** Columns: count, each candidate's votes, votes lost, those elected (";"-separated). */
    private static List<Count> read(Path path, Set<String> elected) throws IOException {
        List<String[]> rows = Files.readAllLines(path).stream().map(line -> line.split(",", -1)).toList();
        String[] header = rows.getFirst();
        List<Count> counts = new ArrayList<>();
        for (String[] row : rows.subList(1, rows.size())) {
            Map<String, Fraction> votes = new LinkedHashMap<>();
            for (int i = 1; i < header.length - 2; i++) {
                votes.put(header[i], Fraction.of(Long.parseLong(row[i])));
            }
            counts.add(new Count(row[0], votes, Fraction.of(Long.parseLong(row[header.length - 2]))));
            if (!row[header.length - 1].isEmpty()) {
                elected.addAll(Arrays.asList(row[header.length - 1].split(";")));
            }
        }
        return counts;
    }
}
