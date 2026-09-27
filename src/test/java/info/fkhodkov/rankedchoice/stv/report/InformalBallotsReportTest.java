package info.fkhodkov.rankedchoice.stv.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.InformalBallots;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.TiedRankingPolicy;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;

/** Every report format shows the ballots set aside before the count, and none mentions them when there are none. */
class InformalBallotsReportTest {

    private static final Candidate X = Candidate.of("X");
    private static final Candidate Y = Candidate.of("Y");
    private static final Candidate Z = Candidate.of("Z");

    /**
     * 5 X-only ballots fall below a minimum of 2 preferences, and 2 ballots tied at first
     * preference are voided by truncation, leaving 6 valid votes: quota floor(6/2)+1 = 4.
     */
    private static StvResult countWithInformalBallots() {
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(X)), BigInteger.valueOf(5)),
                new Ballot(List.of(Set.of(X, Y), Set.of(Z)), BigInteger.valueOf(2)),
                new Ballot(List.of(Set.of(Y), Set.of(X)), BigInteger.valueOf(3)),
                new Ballot(List.of(Set.of(Z), Set.of(X)), BigInteger.valueOf(3)));
        StvRuleset ruleset = new StvRuleset(
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(X, Y, Z)), false, TiedRankingPolicy.TRUNCATE, 2);
        return StvCounter.count(List.of(X, Y, Z), ballots, 1, ruleset);
    }

    @Test
    void theCountRecordsWhyEachBallotWasSetAside() {
        StvResult result = countWithInformalBallots();

        assertThat(result.informal()).isEqualTo(
                new InformalBallots(BigInteger.ZERO, BigInteger.valueOf(2), BigInteger.valueOf(5)));
        assertThat(result.quota()).isEqualTo(Fraction.of(4));
    }

    @Test
    void textReportLeadsWithTheInformalLine() {
        assertThat(StvTextReport.render(countWithInformalBallots(), Exact.INSTANCE))
                .startsWith("Informal ballots: 7 (tied rankings: 2, too few preferences: 5)\nQuota: 4\n\n");
    }

    @Test
    void htmlReportShowsTheInformalLine() {
        assertThat(StvHtmlReport.render(countWithInformalBallots(), Exact.INSTANCE))
                .contains("<p>Informal ballots: 7 (tied rankings: 2, too few preferences: 5)</p>\n<p>Quota: 4</p>");
    }

    @Test
    void csvReportRepeatsTheTotalOnEveryRow() {
        String csv = StvCsvReport.render(countWithInformalBallots(), Exact.INSTANCE);

        assertThat(csv.lines().skip(1)).isNotEmpty().allSatisfy(row -> assertThat(row).endsWith(",7"));
    }

    @Test
    void jsonReportBreaksTheTotalDownByReason() {
        StvJsonReport.Report report = StvJsonReport.toReport(countWithInformalBallots(), Exact.INSTANCE);

        assertThat(report.informalBallots()).isEqualTo(new StvJsonReport.Informal(
                BigInteger.valueOf(7), BigInteger.ZERO, BigInteger.valueOf(2), BigInteger.valueOf(5)));
        assertThat(StvJsonReport.render(countWithInformalBallots(), Exact.INSTANCE))
                .contains("\"informalBallots\" : {\n    \"total\" : 7,");
    }

    @Test
    void textAndHtmlReportsSayNothingWhenEveryBallotIsFormal() {
        StvResult result = new StvResult(List.of(X), List.of(), countWithInformalBallots().quota());

        assertThat(StvTextReport.render(result, Exact.INSTANCE)).doesNotContain("Informal");
        assertThat(StvHtmlReport.render(result, Exact.INSTANCE)).doesNotContain("Informal");
    }
}
