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
 * their fixture files for the source PDFs). Both have more than one candidate meeting quota in
 * round 1, and in both a candidate reaches quota as the first of those surpluses moves: the
 * official count elects them there and then, so the next surplus passes them by, and their
 * final figure is the one they reached. (This engine used to wait for the next round, and so
 * credited them with later transfers too; Coatbridge South in 2017, where that changed the
 * winner, is how it came to light.)
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

        // Round 2 (after Manson's surplus of 210): Cooper still on his 395, Johnson on the
        // official stage 2 figure; Manson's transfer took Macdonald to quota, so she's elected.
        RoundRecord round2 = fixture.result.rounds().get(1);
        assertThat(round2.tallies())
                .containsEntry(cooper, Fraction.of(395))
                .containsEntry(johnson, Fraction.of(3513939, 50000));
        assertThat(round2.action()).isEqualTo(new RoundAction.Elected(cooper, Fraction.of(96)));

        // Macdonald stays on the 379.03609 she was elected with, the official report's final
        // figure for her: Cooper's surplus doesn't reach her.
        RoundRecord round3 = fixture.result.rounds().get(2);
        assertThat(round3.tallies()).containsEntry(macdonald, Fraction.of(37903609, 100000));
        assertThat(round3.action()).isEqualTo(new RoundAction.Elected(macdonald, Fraction.of(8003609, 100000)));
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

        // Round 2 (after Scott's surplus of 137, the largest): Heddle still on his 428 and
        // Macdonald on the official stage 2 figure of 134.02026. Scott's transfer took Dawson to
        // 340.54733, over quota, so he's elected at stage 2, as the official declaration says.
        RoundRecord round2 = fixture.result.rounds().get(1);
        assertThat(round2.tallies())
                .containsEntry(heddle, Fraction.of(428))
                .containsEntry(macdonald, Fraction.of(6701013, 50000));
        assertThat(round2.action()).isEqualTo(new RoundAction.Elected(heddle, Fraction.of(89)));

        // Dawson stays on 340.54733: Heddle's and Shearer's surpluses pass him by.
        RoundRecord round4 = fixture.result.rounds().get(3);
        assertThat(round4.tallies()).containsEntry(dawson, Fraction.of(34054733, 100000));
        assertThat(round4.action()).isEqualTo(new RoundAction.Elected(dawson, Fraction.of(154733, 100000)));
    }

    /**
     * Coatbridge South, 2017: the one ward of 1,070 Scottish council elections where electing a
     * candidate as soon as a surplus transfer takes them to quota changes the winner. MacGregor
     * reaches quota on Carragher's surplus and is elected before Castles's moves, so it passes
     * him by; held back a round, he'd take 2.15 of it, and Brooks would beat Encinias by 0.58 at
     * stage 6 instead of losing by 0.12 (see the fixture file for the official result).
     */
    @Test
    void reproducesThe2017CoatbridgeSouthResult() {
        FixtureResult fixture = run("/testdata/scottish/north-lanarkshire-2017-ward11-coatbridge-south.blt");
        Candidate macgregor = fixture.candidateNamed("Fergus MACGREGOR");
        Candidate encinias = fixture.candidateNamed("Gordon ENCINIAS");
        Candidate brooks = fixture.candidateNamed("James BROOKS");

        assertThat(fixture.result.quota()).isEqualTo(Fraction.of(930));
        assertThat(fixture.result.electedInOrder()).containsExactlyInAnyOrder(
                fixture.candidateNamed("Tracy CARRAGHER"), fixture.candidateNamed("Tom CASTLES"), macgregor, encinias);

        // MacGregor is elected on the 966.37625 Carragher's surplus gave him: Castles's doesn't add to it.
        RoundRecord macgregorElected = fixture.result.rounds().stream()
                .filter(round -> round.action().candidate().equals(macgregor)).findFirst().orElseThrow();
        assertThat(macgregorElected.tallies().get(macgregor)).isEqualTo(Fraction.of(3865505, 4000));

        // Stage 6, as in Mollison's recount: Encinias 517.25, Brooks 517.13, and Brooks goes.
        RoundRecord brooksOut = fixture.result.rounds().stream()
                .filter(round -> round.action().equals(new RoundAction.Eliminated(brooks))).findFirst().orElseThrow();
        assertThat(brooksOut.tallies().get(encinias).toDecimalString(2)).isEqualTo("517.25");
        assertThat(brooksOut.tallies().get(brooks).toDecimalString(2)).isEqualTo("517.13");
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
