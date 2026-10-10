package info.fkhodkov.rankedchoice.stv.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

/**
 * Validates the {@code scottish} ruleset against a 5th real ward, on top of the 4 covered by
 * {@link ScottishRealElectionFixtureTest}: the 2012 Orkney Islands Council election, North
 * Isles ward, sourced the same way (real ballot-level data from MGGG's public
 * {@code scot-elex} archive, cross-checked against Wikipedia's own results table for the
 * ward — see the citation comment in {@code testdata/scottish/orkney-2012-ward6-north-isles.blt}).
 *
 * <p>This ward's distinguishing feature versus the other 4: the final seat isn't decided by a
 * surplus transfer at all. Hagan and Clackson meet quota in round 1's snapshot and are batch
 * elected; Sinclair only crosses quota in the final round, after Skuse (last place throughout)
 * is eliminated and her votes transfer — a "decided by elimination, not surplus" finish the
 * other fixtures don't exercise.
 */
class OrkneyNorthIslesFixtureTest {

    @Test
    void reproducesThe2012NorthIslesResultExactly() {
        ParsedElection election = BltParser.parse(readLines("/testdata/scottish/orkney-2012-ward6-north-isles.blt"));
        List<Candidate> candidates = election.candidates();

        ElectionConfig scottish = ConfigLoader.loadResource("/rulesets/scottish.yaml");
        StvRuleset ruleset = StvRulesetParser.parse(
                scottish.quota(), scottish.transfer(), scottish.rounding(), scottish.tiebreak(), candidates);

        StvResult result = StvCounter.count(candidates, election.ballots(), election.seats(), ruleset);

        Candidate clackson = candidateNamed(candidates, "Stephen Clackson");
        Candidate hagan = candidateNamed(candidates, "Stephen Hagan");
        Candidate sinclair = candidateNamed(candidates, "Graham Sinclair");
        Candidate skuse = candidateNamed(candidates, "Gillian Skuse");

        assertThat(result.quota()).isEqualTo(Fraction.of(264));
        RoundRecord round1 = result.rounds().getFirst();
        assertThat(round1.tallies())
                .containsEntry(hagan, Fraction.of(433))
                .containsEntry(clackson, Fraction.of(308))
                .containsEntry(skuse, Fraction.of(170))
                .containsEntry(sinclair, Fraction.of(144));
        assertThat(result.electedInOrder()).containsExactlyInAnyOrder(hagan, clackson, sinclair);
        assertThat(result.electedInOrder()).doesNotContain(skuse);

        // Sinclair's final election happens only after Skuse's elimination, not via anyone's
        // surplus -- the last round's action is an Elected, not an Eliminated, but reached
        // through the elimination branch rather than a quota-crossing surplus transfer.
        RoundRecord lastRound = result.rounds().getLast();
        assertThat(lastRound.action()).isEqualTo(new RoundAction.Elected(sinclair, Fraction.ZERO));
    }

    private static Candidate candidateNamed(List<Candidate> candidates, String name) {
        return candidates.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No candidate named " + name));
    }

    private static Stream<String> readLines(String resourcePath) {
        InputStream in = OrkneyNorthIslesFixtureTest.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("Missing test resource: " + resourcePath);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines();
    }
}
