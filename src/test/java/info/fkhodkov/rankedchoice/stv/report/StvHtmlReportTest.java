package info.fkhodkov.rankedchoice.stv.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;

class StvHtmlReportTest {

    /** Same election as StvTextReportTest#rendersQuotaRoundsAndElectedCandidates. */
    @Test
    void rendersASelfContainedPageWithOneTablePerRound() {
        Candidate x = Candidate.of("X");
        Candidate y = Candidate.of("Y");
        Candidate z = Candidate.of("Z");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(x)), BigInteger.valueOf(5)),
                new Ballot(List.of(Set.of(y), Set.of(x)), BigInteger.valueOf(3)),
                new Ballot(List.of(Set.of(z), Set.of(x)), BigInteger.valueOf(3)));
        StvRuleset ruleset = new StvRuleset(
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(x, y, z)));

        StvResult result = StvCounter.count(List.of(x, y, z), ballots, 1, ruleset);
        String html = StvHtmlReport.render(result, Exact.INSTANCE);

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<title>STV Result</title>");
        assertThat(html).contains("Quota: 6");
        assertThat(html).contains("<h2>Round 1</h2>");
        assertThat(html).contains("<tr class=\"acted\"><td>Y</td><td>3</td></tr>");
        assertThat(html).contains("<p>Y eliminated</p>");
        assertThat(html).contains("<h2>Round 2</h2>");
        assertThat(html).contains("<tr class=\"acted\"><td>X</td><td>8</td></tr>");
        assertThat(html).contains("<p>X elected</p>");
        assertThat(html).contains("<p>Elected: X</p>");
        assertThat(html).endsWith("</html>\n");
    }

    @Test
    void escapesHtmlSpecialCharactersInCandidateNames() {
        Candidate candidate = new Candidate("A", "Bell & <Whistle>");
        List<Ballot> ballots = List.of(new Ballot(List.of(Set.of(candidate)), BigInteger.valueOf(1)));
        StvRuleset ruleset = new StvRuleset(
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(candidate)));

        StvResult result = StvCounter.count(List.of(candidate), ballots, 1, ruleset);
        String html = StvHtmlReport.render(result, Exact.INSTANCE);

        assertThat(html).contains("Bell &amp; &lt;Whistle&gt;");
        assertThat(html).doesNotContain("Bell & <Whistle>");
    }
}
