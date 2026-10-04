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
 * Two 2022 Scottish council wards against their councils' official Candidate Votes Per Stage
 * Reports (tools/reference/scotland/stage_report.py, which checks 40 such reports): every round
 * is one of the report's stages, every candidate's votes and the non-transferable votes to the
 * report's 5 decimal places. Falkirk's Carse, Kinnaird and Tryst transfers three surpluses before
 * its exclusions; Scottish Borders' Galashiels and District excludes five candidates. Ballots from
 * MGGG's scot-elex.
 */
class ScottishStageReportFixtureTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/scottish");

    @ParameterizedTest
    @ValueSource(strings = {"falkirk-2022-ward4-carse-kinnaird-and-tryst",
        "scottish-borders-2022-ward3-galashiels-and-district"})
    void everyRoundIsAStageOfTheOfficialReport(String name) throws IOException {
        ParsedElection election = BltParser.parse(DIRECTORY.resolve(name + ".blt"));
        StvResult count = StvCounter.count(election.candidates(), election.ballots(), election.seats(),
                StvRulesetParser.parse(ConfigLoader.loadResource("/rulesets/scottish.yaml"), election.candidates()));

        assertThat(CountSheet.read(DIRECTORY.resolve(name + ".stages.txt")).match(count)).doesNotContainNull();
    }
}
