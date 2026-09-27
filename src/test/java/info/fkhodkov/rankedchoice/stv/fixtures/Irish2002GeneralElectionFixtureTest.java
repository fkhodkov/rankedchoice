package info.fkhodkov.rankedchoice.stv.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.config.ConfigLoader;
import info.fkhodkov.rankedchoice.core.config.ElectionConfig;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.config.StvRulesetParser;
import info.fkhodkov.rankedchoice.stv.fixtures.ExpectedStvReplay.ExpectedAction;
import info.fkhodkov.rankedchoice.stv.fixtures.ExpectedStvReplay.ExpectedRound;

/**
 * Validates the engine against real ballot-level data from a jurisdiction not covered
 * elsewhere in this project: three constituencies from Ireland's 2002 general election
 * (Dublin North, Dublin West, Meath), from PrefLib's "Irish Election Data" (series 00001,
 * originally donated by OpenSTV's Jeffrey O'Neill) — see each fixture file's own citation
 * comment for the full source chain.
 *
 * <p>Unlike every other real-election fixture in this project, this is <b>not</b> a replay of
 * each constituency's own official count: real Irish Dáil counts historically transfer
 * surplus votes by physically resampling a random subset of the surplus parcel at full value,
 * not by any deterministic weighted/fractional ratio, so there is no reproducible official
 * round-by-round record to check against at ballot level (and Ireland doesn't publish one in
 * any case). What <em>is</em> independently verifiable, and checked here:
 *
 * <ul>
 * <li>Every first-preference tally below matches Wikipedia's published official result for
 * that constituency exactly.
 * <li>The final elected set matches the TDs actually returned for that constituency exactly.
 * <li>Applying this project's own {@code scottish} ruleset (Droop quota + WIGM, the closest
 * widely-used deterministic equivalent) to the real ballot data reproduces, stage by stage,
 * the exact same figures as an independent implementation of that same deterministic method
 * ({@code jklundell/droop}) — matched to droop's own 5-decimal-place precision at every single
 * stage (see each {@code *.expected.txt} file's own header).
 * </ul>
 */
class Irish2002GeneralElectionFixtureTest {

    @Test
    void reproducesThe2002DublinNorthResult() {
        // The three TDs actually returned for Dublin North in 2002.
        assertReplayMatches("dublin-north-2002", "Trevor Sargent G.P.", "Sean Ryan Lab", "Jim Glennon F.F.");
    }

    @Test
    void reproducesThe2002DublinWestResult() {
        // The three TDs actually returned for Dublin West in 2002.
        assertReplayMatches("dublin-west-2002", "Brian Lenihan F.F.", "Joe Higgins S.P.", "Joan Burton Lab");
    }

    @Test
    void reproducesThe2002MeathResult() {
        // The five TDs actually returned for Meath in 2002.
        assertReplayMatches("meath-2002", "Noel Dempsey F.F.", "Mary Wallace F.F.", "Johnny Brady F.F.",
                "John Bruton F.G.", "Damien English F.G.");
    }

    private static void assertReplayMatches(String fixtureName, String... expectedWinnerNames) {
        ParsedElection election = BltParser.parse(readLines("/testdata/irish/" + fixtureName + ".blt"));
        List<Candidate> candidates = election.candidates();

        ElectionConfig scottish = ConfigLoader.loadResource("/rulesets/scottish.yaml");
        StvRuleset ruleset = StvRulesetParser.parse(
                scottish.quota(), scottish.transfer(), scottish.rounding(), scottish.tiebreak(), candidates);
        StvResult actual = StvCounter.count(candidates, election.ballots(), election.seats(), ruleset);

        List<Candidate> expectedWinners = List.of(expectedWinnerNames).stream()
                .map(name -> candidateNamed(candidates, name))
                .toList();
        assertThat(actual.electedInOrder()).as(fixtureName + ", winners")
                .containsExactlyInAnyOrderElementsOf(expectedWinners);

        ExpectedStvReplay expected = ExpectedStvReplay.parse(
                readLines("/testdata/irish/" + fixtureName + ".expected.txt"));

        assertThat(actual.quota()).as(fixtureName).isEqualTo(expected.quota());
        assertThat(actual.rounds()).as(fixtureName).hasSameSizeAs(expected.rounds());

        for (int i = 0; i < expected.rounds().size(); i++) {
            RoundRecord actualRound = actual.rounds().get(i);
            ExpectedRound expectedRound = expected.rounds().get(i);
            assertRoundMatches(fixtureName, candidates, actualRound, expectedRound);
        }
    }

    private static void assertRoundMatches(String fixtureName, List<Candidate> candidates,
            RoundRecord actualRound, ExpectedRound expectedRound) {
        String context = fixtureName + ", round " + expectedRound.roundNumber();
        assertThat(actualRound.roundNumber()).as(context).isEqualTo(expectedRound.roundNumber());

        Map<String, Fraction> actualTalliesByName = new LinkedHashMap<>();
        actualRound.tallies().forEach((candidate, tally) -> actualTalliesByName.put(candidate.name(), tally));
        expectedRound.tallies().forEach((name, expectedTally) -> assertThat(actualTalliesByName)
                .as(context + ", " + name)
                .containsEntry(name, expectedTally));

        assertThat(actualRound.exhaustedVotes()).as(context + ", exhausted").isEqualTo(expectedRound.exhausted());

        assertActionMatches(candidates, context, actualRound.action(), expectedRound.action());
    }

    private static void assertActionMatches(
            List<Candidate> candidates, String context, RoundAction actual, ExpectedAction expected) {
        Candidate expectedCandidate = candidateNamed(candidates, expected.candidateName());
        assertThat(actual.candidate()).as(context + ", action candidate").isEqualTo(expectedCandidate);

        switch (expected) {
            case ExpectedAction.Elected(_, var expectedSurplus) -> {
                assertThat(actual).as(context + ", action type").isInstanceOf(RoundAction.Elected.class);
                expectedSurplus.ifPresent(surplus -> assertThat(((RoundAction.Elected) actual).surplusTransferred())
                        .as(context + ", surplus")
                        .isEqualTo(surplus));
            }
            case ExpectedAction.Eliminated _ ->
                    assertThat(actual).as(context + ", action type").isInstanceOf(RoundAction.Eliminated.class);
        }
    }

    private static Candidate candidateNamed(List<Candidate> candidates, String name) {
        return candidates.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No candidate named " + name));
    }

    private static Stream<String> readLines(String resourcePath) {
        InputStream in = Irish2002GeneralElectionFixtureTest.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("Missing test resource: " + resourcePath);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines();
    }
}
