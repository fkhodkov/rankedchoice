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
 * Validates the engine against real ballot-level data, not hand-derived or adapted fixtures.
 * Each {@code testdata/scottish/*.blt} file is a real preference profile from MGGG's public
 * {@code scot-elex} archive (arXiv:2605.12676); the expected figures below are independently
 * cross-checked against each ward's published/declared result, not derived from our own
 * engine — see the citation comment in each fixture file for the source.
 *
 * <p>The Shetland North and Orkney Kirkwall East tests below also spot-check a couple of
 * intermediate rounds against each ward's official "Candidate Votes Per Stage Report" (see
 * their fixture files for the source PDFs). Unlike
 * {@link BadenochAndStrathspeyFullReplayTest}, this isn't a full stage-by-stage replay: both
 * of these elections have more than one candidate meeting quota in the very same tally
 * snapshot (round 1), and the official eCount system re-checks for newly-qualifying
 * candidates immediately after each individual surplus transfer — sometimes electing one
 * within the very same stage that transferred another's surplus. Our engine only re-runs that
 * check at the start of the next full round, so a candidate who becomes eligible as a side
 * effect of someone else's transfer is elected one round later than the official stage
 * numbering would suggest. The tallies and final winners still agree exactly; only the
 * round/stage chunking differs, so a strict round-count-for-round-count comparison (as
 * Badenoch supports) isn't meaningful here.
 */
class ScottishRealElectionFixtureTest {

    @Test
    void reproducesThe2012ShetlandSouthResultExactly() {
        FixtureResult fixture = run("/testdata/scottish/shetland-2012-ward5-south.blt");
        Candidate duncan = fixture.candidateNamed("Allison Duncan");
        Candidate smith = fixture.candidateNamed("George Smith");
        Candidate fox = fixture.candidateNamed("Billy Fox");
        Candidate shearer = fixture.candidateNamed("Andrew Shearer");

        assertThat(fixture.result.quota()).isEqualTo(Fraction.of(378));
        assertThat(fixture.result.rounds().getFirst().tallies())
                .containsEntry(duncan, Fraction.of(685))
                .containsEntry(smith, Fraction.of(418))
                .containsEntry(fox, Fraction.of(337))
                .containsEntry(shearer, Fraction.of(68));
        assertThat(fixture.result.electedInOrder()).containsExactlyInAnyOrder(duncan, smith, fox);
    }

    @Test
    void reproducesThe2017ShetlandNorthResultExactly() {
        FixtureResult fixture = run("/testdata/scottish/shetland-2017-ward2-north.blt");
        Candidate cooper = fixture.candidateNamed("Alastair Thomas James Cooper");
        Candidate johnson = fixture.candidateNamed("Isobel Johnson");
        Candidate macdonald = fixture.candidateNamed("Emma Louise Macdonald");
        Candidate manson = fixture.candidateNamed("Andrea Isabel Manson");

        assertThat(fixture.result.quota()).isEqualTo(Fraction.of(299));
        assertThat(fixture.result.rounds().getFirst().tallies())
                .containsEntry(manson, Fraction.of(509))
                .containsEntry(cooper, Fraction.of(395))
                .containsEntry(macdonald, Fraction.of(240))
                .containsEntry(johnson, Fraction.of(48));
        assertThat(fixture.result.electedInOrder()).containsExactlyInAnyOrder(cooper, manson, macdonald);

        // Round 2 (Manson elected first, largest surplus 210): matches the official Stage 2
        // table exactly, including Cooper's tally staying frozen at his round-1 figure of 395
        // rather than being inflated by Manson's transfer — the batch-election fix this test
        // suite exists to guard against regressing.
        RoundRecord round2 = fixture.result.rounds().get(1);
        assertThat(round2.tallies())
                .containsEntry(cooper, Fraction.of(395))
                .containsEntry(macdonald, Fraction.of(37903609, 100000))
                .containsEntry(johnson, Fraction.of(3513939, 50000));
        assertThat(round2.action()).isEqualTo(new RoundAction.Elected(cooper, Fraction.of(96)));

        // Round 3: Macdonald's final tally, after both Manson's and Cooper's surpluses have
        // been transferred, matches the official report's final figure for her (433.23178).
        RoundRecord round3 = fixture.result.rounds().get(2);
        assertThat(round3.action()).isEqualTo(new RoundAction.Elected(macdonald, Fraction.of(6711589, 50000)));
        assertThat(round3.tallies()).containsEntry(macdonald, Fraction.of(21661589, 50000));
    }

    @Test
    void reproducesThe2022OrkneyKirkwallEastResultExactly() {
        FixtureResult fixture = run("/testdata/scottish/orkney-2022-ward1-kirkwall-east.blt");
        Candidate dawson = fixture.candidateNamed("David Dawson");
        Candidate heddle = fixture.candidateNamed("Steven Heddle");
        Candidate macdonald = fixture.candidateNamed("Graham Macdonald");
        Candidate scott = fixture.candidateNamed("John Ross Scott");
        Candidate shearer = fixture.candidateNamed("Gwenda Shearer");

        assertThat(fixture.result.quota()).isEqualTo(Fraction.of(339));
        assertThat(fixture.result.rounds().getFirst().tallies())
                .containsEntry(dawson, Fraction.of(285))
                .containsEntry(heddle, Fraction.of(428))
                .containsEntry(macdonald, Fraction.of(92))
                .containsEntry(scott, Fraction.of(476))
                .containsEntry(shearer, Fraction.of(413));
        assertThat(fixture.result.electedInOrder()).containsExactlyInAnyOrder(dawson, heddle, scott, shearer);

        // Round 2 (Scott elected first, largest surplus 137): matches the official Stage 2
        // table exactly, including Dawson's 340.54733 and Macdonald's 134.02026, and Heddle's
        // tally staying frozen at his round-1 figure of 428 rather than being inflated by
        // Scott's transfer — the batch-election fix this test suite exists to guard against
        // regressing (Heddle and Shearer also met quota in round 1's snapshot, alongside
        // Scott).
        RoundRecord round2 = fixture.result.rounds().get(1);
        assertThat(round2.tallies())
                .containsEntry(heddle, Fraction.of(428))
                .containsEntry(dawson, Fraction.of(34054733, 100000))
                .containsEntry(macdonald, Fraction.of(6701013, 50000));
        assertThat(round2.action()).isEqualTo(new RoundAction.Elected(heddle, Fraction.of(89)));

        // Round 4: Dawson's final election, after Scott's, Heddle's, and Shearer's surpluses
        // have all been transferred, with a surplus of 10227569/100000 (10.22757).
        RoundRecord round4 = fixture.result.rounds().get(3);
        assertThat(round4.action()).isEqualTo(new RoundAction.Elected(dawson, Fraction.of(10227569, 100000)));
    }

    private record FixtureResult(StvResult result, List<Candidate> candidates) {
        Candidate candidateNamed(String name) {
            return candidates.stream()
                    .filter(candidate -> candidate.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No candidate named " + name));
        }
    }

    private static FixtureResult run(String resourcePath) {
        ParsedElection election = BltParser.parse(readLines(resourcePath));
        List<Candidate> candidates = election.candidates();

        ElectionConfig scottish = ConfigLoader.loadResource("/rulesets/scottish.yaml");
        StvRuleset ruleset = StvRulesetParser.parse(
                scottish.quota(), scottish.transfer(), scottish.rounding(), scottish.tiebreak(), candidates);

        StvResult result = StvCounter.count(candidates, election.ballots(), election.seats(), ruleset);
        return new FixtureResult(result, candidates);
    }

    private static Stream<String> readLines(String resourcePath) {
        InputStream in = ScottishRealElectionFixtureTest.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("Missing test resource: " + resourcePath);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines();
    }
}
