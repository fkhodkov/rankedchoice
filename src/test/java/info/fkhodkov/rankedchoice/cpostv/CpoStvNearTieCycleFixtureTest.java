package info.fkhodkov.rankedchoice.cpostv;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;

/**
 * A realistic-size CPO-STV count (8 candidates, 4 seats, 10,000 synthetic voters; see the
 * fixture's header) checked against an independent implementation: {@code
 * tools/reference/cpo_stv.py}, written from the method's rules alone, reproduces every one of
 * this count's 4,830 pairwise scores exactly, and an independent Schulze winner computation over
 * them picks the same committee.
 *
 * <p>The election is a 52/48 two-party race in which every Red candidate is about equally
 * popular, so committees differing by one Red candidate come within a few votes of each other
 * and their head-to-head comparisons cycle:
 * <pre>
 *   {B1,B4,R1,R3} beats {B1,B4,R3,R4}   7298.76 to 7284.75
 *   {B1,B4,R3,R4} beats {B1,B4,R2,R3}   7299.05 to 7284.10
 *   {B1,B4,R2,R3} beats {B1,B4,R1,R3}   7295.57 to 7286.40
 * </pre>
 * The last defeat is the weakest link, so the beatpath completion elects {B1,B4,R1,R3} -- even
 * though it loses head to head to {B1,B4,R2,R3}, which is the committee every classic STV
 * variant elects here. That's correct under CPO-STV's rules, and the kind of case where it
 * genuinely differs from STV.
 */
class CpoStvNearTieCycleFixtureTest {

    @Test
    void reproducesTheIndependentlyCheckedScoresAndWinner() {
        ParsedElection election = BltParser.parse(Path.of("src/test/resources/testdata/cpostv/near-tie-cycle.blt"));
        List<Candidate> candidates = election.candidates();
        CpoStvResult result = CpoStvCounter.count(candidates, election.ballots(), election.seats(),
                new CpoStvRuleset(DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE));

        Set<Candidate> withR1 = outcome(candidates, "B1", "B4", "R1", "R3");
        Set<Candidate> withR2 = outcome(candidates, "B1", "B4", "R2", "R3");
        Set<Candidate> withR4 = outcome(candidates, "B1", "B4", "R3", "R4");
        Map<Set<Candidate>, Map<Set<Candidate>, Fraction>> scores = result.outcomeScores();

        assertThat(scores.get(withR1).get(withR4)).isEqualTo(Fraction.of(4875571, 668));
        assertThat(scores.get(withR4).get(withR1)).isEqualTo(Fraction.of(4866209, 668));
        assertThat(scores.get(withR4).get(withR2)).isEqualTo(Fraction.of(14656501, 2008));
        assertThat(scores.get(withR2).get(withR4)).isEqualTo(Fraction.of(14626465, 2008));
        assertThat(scores.get(withR2).get(withR1)).isEqualTo(Fraction.of(4909921, 673));
        assertThat(scores.get(withR1).get(withR2)).isEqualTo(Fraction.of(4903744, 673));

        assertThat(result.winners()).containsExactly(withR1);
    }

    private static Set<Candidate> outcome(List<Candidate> candidates, String... names) {
        Set<String> wanted = Set.of(names);
        return Set.copyOf(candidates.stream().filter(candidate -> wanted.contains(candidate.name())).toList());
    }
}
