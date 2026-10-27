package info.fkhodkov.rankedchoice.stv.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
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
 * The Electoral Reform Society's worked "model election" from the ERS97 rulebook (11
 * candidates, 6 seats, 753 votes), counted with the {@code ers97} ruleset and replayed against
 * the rulebook's own count sheet (see the fixture files' headers): every round matches one of
 * its stages, every candidate's votes and the non-transferable votes (exhausted plus lost to
 * rounding), and the same six candidates are elected.
 *
 * <p>It takes every ERS97 provision the ruleset has. Monk's exclusion goes value group by value
 * group, and Duke stops receiving votes on reaching quota partway through (108.68, not the
 * 108.89 a single-shot exclusion gives). Glazier and Wright are excluded together (bulk
 * exclusion; one at a time, Baron would overtake Vicar for the last seat). Duke's surplus of
 * 1.10 waits throughout, since it could never change who is excluded. And Prince (104.31) and
 * Freeman (101.62) are elected on the falling "votes required for election", below the quota.
 *
 * <p>The sheet has more stages than this count has rounds: it records each value group of an
 * exclusion as a stage of its own (stages 3 and 5).
 */
class Ers97ModelElectionFixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/ers97");

    private static List<Candidate> candidates;
    private static StvResult result;

    @BeforeAll
    static void count() {
        ParsedElection election = BltParser.parse(DIRECTORY.resolve("ers97-model-election.blt"));
        candidates = election.candidates();
        result = StvCounter.count(candidates, election.ballots(), election.seats(),
                StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/ers97.yaml"), candidates));
    }

    @Test
    void everyRoundMatchesAStageOfTheRulebooksCountSheet() throws IOException {
        CountSheet sheet = CountSheet.read(DIRECTORY.resolve("ers97-model-election.rulebook.txt"));
        assertThat(result.quota()).isEqualTo(Fraction.of(5379, 50)); // 107.58

        List<Integer> matched = sheet.match(result);
        assertThat(matched).doesNotContainNull()
                // Every stage that ends a step, so the whole count is covered.
                .contains(1, 2, 4, 6, 7, 8);
        assertThat(result.electedInOrder()).containsExactlyInAnyOrder(
                named("Smith"), named("Duke"), named("Carpenter"), named("Prince"), named("Freeman"), named("Vicar"));
    }

    /**
     * Four synthetic elections counted by pyRCV2's ERS97 counter (see the files' headers),
     * picked for what they exercise: r36 elects C2 and C3 in one step, C3 on 214.00 as the votes
     * required falls from 235.34 to 213.50; r20 fills its last seat with C3's surplus still to
     * transfer, which ends the count; r86 defers C2's surplus and excludes C6 and C4 together;
     * ot11 (one of tools/reference/opentally's random elections) needs the votes required rounded
     * up, 29.59 / 3 to 9.87, so that C4 on 9.86 waits for C9's surplus.
     */
    @ParameterizedTest
    @ValueSource(strings = {"r20", "r36", "r86", "ot11"})
    void everyRoundMatchesPyRcv2(String name) throws IOException {
        ParsedElection election = BltParser.parse(DIRECTORY.resolve("pyrcv2/" + name + ".blt"));
        StvResult count = StvCounter.count(election.candidates(), election.ballots(), election.seats(),
                StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/ers97.yaml"), election.candidates()));
        CountSheet sheet = CountSheet.read(DIRECTORY.resolve("pyrcv2/" + name + ".stages.txt"));

        assertThat(sheet.match(count)).doesNotContainNull();
    }

    @Test
    void glazierAndWrightAreExcludedTogether() {
        List<RoundRecord> exclusions = result.rounds().stream()
                .filter(round -> round.action().equals(new RoundAction.Eliminated(named("Glazier")))
                        || round.action().equals(new RoundAction.Eliminated(named("Wright"))))
                .toList();
        assertThat(exclusions).hasSize(2);
        // One stage, decided on one set of tallies.
        assertThat(exclusions.get(0).tallies()).isEqualTo(exclusions.get(1).tallies());
    }

    @Test
    void dukesSurplusWaitsToTheEnd() {
        RoundRecord duke = result.rounds().stream()
                .filter(round -> round.action().candidate().equals(named("Duke")))
                .findFirst().orElseThrow();
        // Elected partway through Monk's exclusion on 108.68; the 1.10 above quota never moves.
        assertThat(duke.tallies().get(named("Duke"))).isEqualTo(Fraction.of(2717, 25));
        assertThat(duke.action()).isEqualTo(new RoundAction.Elected(named("Duke"), Fraction.ZERO));
    }

    private static Candidate named(String name) {
        return candidates.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
    }
}
