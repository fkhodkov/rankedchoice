package info.fkhodkov.rankedchoice.stv.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;

class StvTextReportTest {

    @Test
    void rendersQuotaRoundsAndElectedCandidates() {
        Candidate x = Candidate.of("X");
        Candidate y = Candidate.of("Y");
        Candidate z = Candidate.of("Z");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(x)), BigInteger.valueOf(5)),
                new Ballot(List.of(Set.of(y), Set.of(x)), BigInteger.valueOf(3)),
                new Ballot(List.of(Set.of(z), Set.of(x)), BigInteger.valueOf(3)));
        StvRuleset ruleset = new StvRuleset(
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, new DeclaredOrderTieBreak(List.of(x, y, z)));

        StvResult result = StvCounter.count(List.of(x, y, z), ballots, 1, ruleset);

        String expected = """
                Quota: 6

                Round 1
                  X: 5
                  Y: 3
                  Z: 3
                  -> Y eliminated

                Round 2
                  X: 8
                  Z: 3
                  -> X elected (surplus 2 transferred)

                Elected: X
                """;

        assertThat(StvTextReport.render(result)).isEqualTo(expected);
    }

    @Test
    void showsExhaustedVotesOnceAnyAppear() {
        // B's only ballot ranks nobody else, so eliminating B exhausts it (1 vote) with
        // nowhere further to go; that shows up in every later round's snapshot.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(a), Set.of(c)), BigInteger.valueOf(2)),
                new Ballot(List.of(Set.of(b)), BigInteger.valueOf(1)),
                new Ballot(List.of(Set.of(c)), BigInteger.valueOf(2)));
        StvRuleset ruleset = new StvRuleset(
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, new DeclaredOrderTieBreak(List.of(a, b, c)));

        StvResult result = StvCounter.count(List.of(a, b, c), ballots, 1, ruleset);
        String report = StvTextReport.render(result);

        assertThat(report).contains("exhausted so far: 1");
    }
}
