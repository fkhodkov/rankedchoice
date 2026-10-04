package info.fkhodkov.rankedchoice.stv.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.config.ConfigLoader;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.config.StvRulesetParser;

/**
 * The Victorian and Western Australian Legislative Council rulesets, which have no published
 * ballots to replay, against OpenTally's count of four of tools/reference/opentally's random
 * elections (see the files' headers): every round of this count is one of OpenTally's stages,
 * every candidate's votes and the non-transferable votes exactly. Picked for what they exercise:
 * in vic-lc-e11, an excluded candidate's first preferences move before the papers they received
 * at a value of 1; in wa-lc-e11 and wa-lc-e154, their parcels move one at a time in the order
 * received; in vic-lc-e170, C4 and C7 tie on 37 for exclusion, and the last count at which they
 * differed is partway through C9's exclusion (C7 on 36), not the start of a round (C4 on 30).
 */
class OpenTallyFixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/opentally");

    @ParameterizedTest
    @ValueSource(strings = {"vic-lc-e11", "vic-lc-e170", "wa-lc-e11", "wa-lc-e154"})
    void everyRoundMatchesOpenTally(String name) throws IOException {
        String ruleset = name.substring(0, name.lastIndexOf("-e"));
        ParsedElection election = BltParser.parse(DIRECTORY.resolve(name + ".blt"));
        StvResult count = StvCounter.count(election.candidates(), election.ballots(), election.seats(),
                StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/" + ruleset + ".yaml"),
                        election.candidates()));

        assertThat(CountSheet.read(DIRECTORY.resolve(name + ".stages.txt")).match(count)).doesNotContainNull();
    }
}
