package info.fkhodkov.rankedchoice.cpostv;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.quota.HagenbachBischoffQuota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;

class CpoStvTextReportTest {

    @Test
    void rendersTheWinningOutcomeAndFullRanking() {
        Candidate a = Candidate.of("Andrea");
        Candidate b = Candidate.of("Brad");
        Candidate c = Candidate.of("Carter");
        List<Ballot> ballots = List.of(new Ballot(List.of(Set.of(a), Set.of(b), Set.of(c)), BigInteger.valueOf(10)));
        CpoStvRuleset ruleset =
                new CpoStvRuleset(HagenbachBischoffQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE);

        CpoStvResult result = CpoStvCounter.count(List.of(a, b, c), ballots, 2, ruleset);
        String report = CpoStvTextReport.render(result);

        assertThat(report).contains("Winning outcome: {Andrea, Brad}");
        assertThat(report).contains("Full ranking:");
        assertThat(report).contains("1. {Andrea, Brad}");
    }
}
