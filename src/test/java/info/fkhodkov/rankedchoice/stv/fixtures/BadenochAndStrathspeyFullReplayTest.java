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
 * Replays the full 2022 Badenoch and Strathspey count (Highland Council) round by round and
 * checks every stage against {@code highland-2022-badenoch-and-strathspey.expected.txt} —
 * transcribed from Highland's official "Candidate Votes Per Stage Report", not derived from
 * our own engine. Unlike {@link ScottishRealElectionFixtureTest}, which only checks first
 * preferences and the final winners, this asserts every round's tallies, action, and
 * exhausted-vote count against the official record.
 */
class BadenochAndStrathspeyFullReplayTest {

    @Test
    void replaysEveryStageAgainstTheOfficialRecord() {
        ParsedElection election = BltParser.parse(
                readLines("/testdata/scottish/highland-2022-badenoch-and-strathspey.blt"));
        List<Candidate> candidates = election.candidates();

        ElectionConfig scottish = ConfigLoader.loadResource("/rulesets/scottish.yaml");
        StvRuleset ruleset = StvRulesetParser.parse(
                scottish.quota(), scottish.transfer(), scottish.rounding(), scottish.tiebreak(), candidates);
        StvResult actual = StvCounter.count(candidates, election.ballots(), election.seats(), ruleset);

        ExpectedStvReplay expected = ExpectedStvReplay.parse(
                readLines("/testdata/scottish/highland-2022-badenoch-and-strathspey.expected.txt"));

        assertThat(actual.quota()).isEqualTo(expected.quota());
        assertThat(actual.rounds()).hasSameSizeAs(expected.rounds());

        for (int i = 0; i < expected.rounds().size(); i++) {
            RoundRecord actualRound = actual.rounds().get(i);
            ExpectedRound expectedRound = expected.rounds().get(i);
            assertRoundMatches(candidates, actualRound, expectedRound);
        }
    }

    private static void assertRoundMatches(
            List<Candidate> candidates, RoundRecord actualRound, ExpectedRound expectedRound) {
        String context = "round " + expectedRound.roundNumber();
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
        InputStream in = BadenochAndStrathspeyFullReplayTest.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("Missing test resource: " + resourcePath);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines();
    }
}
