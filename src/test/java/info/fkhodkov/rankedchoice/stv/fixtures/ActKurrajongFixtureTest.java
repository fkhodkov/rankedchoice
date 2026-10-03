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
 * Validates the {@code act-hare-clark} ruleset against real ballot-level data from the 2020
 * ACT Legislative Assembly election, Kurrajong electorate (28 candidates, 5 seats, 50,600
 * ballots) — see the citation comment in {@code testdata/act/act-2020-kurrajong.blt} for
 * provenance. Round 2 (every candidate's tally right after Andrew Barr's surplus is
 * transferred) is checked against the official "Final Distribution of Preferences" report to
 * the full 6 decimal places specified by the ACT Electoral Act — this is the round that
 * exercises both of this ruleset's distinguishing rules: excluding non-transferable papers
 * from the transfer-value denominator, and rounding each destination candidate's own gain
 * rather than the shared ratio (see {@link
 * info.fkhodkov.rankedchoice.stv.transfer.LastParcelDeferredRoundingTransfer}).
 *
 * <p>Unlike {@link BadenochAndStrathspeyFullReplayTest}, this doesn't attempt a full
 * round-by-round replay: the official count splits a multi-value-group exclusion into
 * several separate "counts" (one value-group at a time), which this engine's
 * one-action-per-round model doesn't replicate — a structural reporting difference, not a
 * correctness gap (see the fixture file's citation comment for detail).
 */
class ActKurrajongFixtureTest {

    @Test
    void reproducesThe2020KurrajongResultExactly() {
        ParsedElection election = BltParser.parse(readLines("/testdata/act/act-2020-kurrajong.blt"));
        List<Candidate> candidates = election.candidates();

        ElectionConfig actHareClark = ConfigLoader.loadResource("/rulesets/act-hare-clark.yaml");
        StvRuleset ruleset = StvRulesetParser.parse(
                actHareClark.quota(), actHareClark.transfer(), actHareClark.rounding(), actHareClark.tiebreak(),
                candidates);

        StvResult result = StvCounter.count(candidates, election.ballots(), election.seats(), ruleset);

        Candidate barr = candidateNamed(candidates, "Andrew Barr");
        Candidate rattenbury = candidateNamed(candidates, "Shane Rattenbury");
        Candidate stephenSmith = candidateNamed(candidates, "Rachel Stephen-Smith");
        Candidate lee = candidateNamed(candidates, "Elizabeth Lee");
        Candidate vassarotti = candidateNamed(candidates, "Rebecca Vassarotti");

        assertThat(result.quota()).isEqualTo(Fraction.of(8434));
        assertThat(result.rounds().getFirst().tallies())
                .containsEntry(barr, Fraction.of(11148))
                .containsEntry(rattenbury, Fraction.of(6388))
                .containsEntry(lee, Fraction.of(5040))
                .containsEntry(vassarotti, Fraction.of(3093))
                .containsEntry(stephenSmith, Fraction.of(2786));
        assertThat(result.rounds().getFirst().action()).isEqualTo(new RoundAction.Elected(barr, Fraction.of(2714)));
        assertThat(result.electedInOrder())
                .containsExactlyInAnyOrder(barr, rattenbury, stephenSmith, lee, vassarotti);

        // Round 2: every other candidate's tally after Barr's surplus is transferred, matching
        // the official report's "Count 2" table exactly, to all 6 decimal places.
        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies())
                .containsEntry(rattenbury, Fraction.of(6703439228L, 1_000_000))
                .containsEntry(lee, Fraction.of(5102843319L, 1_000_000))
                .containsEntry(candidateNamed(candidates, "Candice Burch"), Fraction.of(4011255608L, 1_000_000))
                .containsEntry(stephenSmith, Fraction.of(3708354085L, 1_000_000))
                .containsEntry(vassarotti, Fraction.of(3142394359L, 1_000_000))
                .containsEntry(candidateNamed(candidates, "Maddy Northam"), Fraction.of(2529497792L, 1_000_000))
                .containsEntry(candidateNamed(candidates, "Patrick Pentony"), Fraction.of(2402584016L, 1_000_000))
                .containsEntry(candidateNamed(candidates, "Jacob Ingram"), Fraction.of(2226031173L, 1_000_000))
                .containsEntry(candidateNamed(candidates, "Judy Anderson"), Fraction.of(1655384358L, 1_000_000))
                .containsEntry(candidateNamed(candidates, "Robert Johnson"), Fraction.of(1640715379L, 1_000_000))
                .containsEntry(candidateNamed(candidates, "Adriana Boisen"), Fraction.of(1277386971L, 1_000_000))
                .containsEntry(candidateNamed(candidates, "Rattesh Gumber"), Fraction.of(934379583L, 1_000_000))
                .containsEntry(candidateNamed(candidates, "Michael Brewer"), Fraction.of(930408865L, 1_000_000));
    }

    private static Candidate candidateNamed(List<Candidate> candidates, String name) {
        return candidates.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No candidate named " + name));
    }

    private static Stream<String> readLines(String resourcePath) {
        InputStream in = ActKurrajongFixtureTest.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("Missing test resource: " + resourcePath);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines();
    }
}
