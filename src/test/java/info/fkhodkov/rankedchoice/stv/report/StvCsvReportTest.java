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

class StvCsvReportTest {

    /** Same election as StvTextReportTest#rendersQuotaRoundsAndElectedCandidates, reshaped as CSV. */
    @Test
    void rendersOneRowPerCandidatePerRound() {
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
        String csv = StvCsvReport.render(result, Exact.INSTANCE);

        String expected = """
                round,candidate,tally,action,exhausted_votes,rounding_loss,informal_ballots
                1,X,5,,0,0,0
                1,Y,3,eliminated,0,0,0
                1,Z,3,,0,0,0
                2,X,8,elected,0,0,0
                2,Z,3,,0,0,0
                """;

        assertThat(csv).isEqualTo(expected);
    }

    @Test
    void quotesCandidateNamesThatContainCommas() {
        Candidate smith = new Candidate("A", "Smith, Jane");
        Candidate jones = Candidate.of("Jones");
        List<Ballot> ballots = List.of(new Ballot(List.of(Set.of(smith)), BigInteger.valueOf(5)));
        StvRuleset ruleset = new StvRuleset(
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(smith, jones)));

        StvResult result = StvCounter.count(List.of(smith, jones), ballots, 1, ruleset);
        String csv = StvCsvReport.render(result, Exact.INSTANCE);

        assertThat(csv).contains("\"Smith, Jane\"");
    }
}
