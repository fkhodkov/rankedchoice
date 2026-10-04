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
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;
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
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(x, y, z)));

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
                  -> X elected

                Elected: X
                """;

        assertThat(StvTextReport.render(result, Exact.INSTANCE)).isEqualTo(expected);
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
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(a, b, c)));

        StvResult result = StvCounter.count(List.of(a, b, c), ballots, 1, ruleset);
        String report = StvTextReport.render(result, Exact.INSTANCE);

        assertThat(report).contains("exhausted so far: 1");
    }

    @Test
    void rendersFractionalTalliesAsDecimalWithTheRulesetsFixedPrecision() {
        // Same setup as StvCounterTest's roundingRuleIsAppliedToTheTransferValue: 13 voters,
        // 2 seats, quota 5. A's surplus of 6 transfers at a truncated ratio of 6/11 -> 0.54,
        // giving B 11 * 0.54 = 5.94 -- reported as "5.94" (not "297/50"), and every other
        // figure padded to the same 2 decimal places ("11" -> "11.00") so the decimal points
        // line up; values are also right-padded to a common width ("5.94" gains a leading
        // space to match "11.00"'s length). The 0.06 of A's surplus the truncated ratio doesn't
        // pass on (6 - 5.94) is reported as lost to rounding.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(a), Set.of(b)), BigInteger.valueOf(11)),
                new Ballot(List.of(Set.of(c)), BigInteger.valueOf(2)));
        StvRuleset ruleset = new StvRuleset(
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, new TruncateDecimal(2),
                new DeclaredOrderTieBreak(List.of(a, b, c)));

        StvResult result = StvCounter.count(List.of(a, b, c), ballots, 2, ruleset);
        String report = StvTextReport.render(result, ruleset.roundingRule());

        String expected = """
                Quota: 5.00

                Round 1
                  A: 11.00
                  C:  2.00
                  B:  0.00
                  -> A elected (surplus 6.00 transferred)

                Round 2
                  B:  5.94
                  C:  2.00
                  -> B elected
                  (lost to rounding so far: 0.06)

                Elected: A, B
                """;

        assertThat(report).isEqualTo(expected);
    }

    @Test
    void padsCandidateNamesIntoAColumnAcrossEveryRound() {
        // Same shape as rendersQuotaRoundsAndElectedCandidates, but with names of different
        // lengths -- every name should be right-padded to the longest one's width ("Bo" and
        // "Ann" gain trailing spaces to match "Charlotte"), consistently across both rounds
        // even though Charlotte only appears in round 2.
        Candidate ann = Candidate.of("Ann");
        Candidate bo = Candidate.of("Bo");
        Candidate charlotte = Candidate.of("Charlotte");
        List<Ballot> ballots = List.of(
                new Ballot(List.of(Set.of(ann)), BigInteger.valueOf(5)),
                new Ballot(List.of(Set.of(bo), Set.of(ann)), BigInteger.valueOf(3)),
                new Ballot(List.of(Set.of(charlotte), Set.of(ann)), BigInteger.valueOf(3)));
        StvRuleset ruleset = new StvRuleset(
                DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE,
                new DeclaredOrderTieBreak(List.of(ann, bo, charlotte)));

        StvResult result = StvCounter.count(List.of(ann, bo, charlotte), ballots, 1, ruleset);
        String report = StvTextReport.render(result, Exact.INSTANCE);

        String expected = """
                Quota: 6

                Round 1
                  Ann      : 5
                  Bo       : 3
                  Charlotte: 3
                  -> Bo eliminated

                Round 2
                  Ann      : 8
                  Charlotte: 3
                  -> Ann elected

                Elected: Ann
                """;

        assertThat(report).isEqualTo(expected);
    }
}
