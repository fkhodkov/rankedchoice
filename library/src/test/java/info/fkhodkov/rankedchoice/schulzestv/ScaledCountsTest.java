package info.fkhodkov.rankedchoice.schulzestv;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.schulze.PairwiseMatrix;
import info.fkhodkov.rankedchoice.schulze.SchulzeMethod;
import info.fkhodkov.rankedchoice.schulze.SchulzeResult;

/**
 * The pairwise matrix ({@link PairwiseMatrix}) and Schulze STV's ballot groups ({@link
 * SchulzeStvCounter.IndexedBallots}) are counted in {@code long}s when the counts fit, and in
 * {@link BigInteger}s when they don't: two implementations of each, and no real election is big
 * enough to reach the second. So random elections (with tied and truncated rankings) are counted
 * as they are and with every ballot's count multiplied by 10^19, past {@code long} for any single
 * ballot. Every figure these methods report is a sum of ballot counts or a ratio of such sums of
 * the same degree, so the large count must report exactly 10^19 times each of the small count's
 * pairwise and path figures and supports, and the same rankings, committees and winners.
 */
class ScaledCountsTest {

    private static final BigInteger SCALE = BigInteger.TEN.pow(19);
    private static final Fraction SCALE_FRACTION = Fraction.of(SCALE);

    /** A random election, as it is and with every count scaled. */
    private record Election(List<Candidate> candidates, List<Ballot> small, List<Ballot> large) {

        static Election random(Random random, int minCandidates, int maxCandidates) {
            int candidateCount = minCandidates + random.nextInt(maxCandidates - minCandidates + 1);
            List<Candidate> candidates = new ArrayList<>();
            for (int i = 0; i < candidateCount; i++) {
                candidates.add(Candidate.of("C" + i));
            }
            List<Ballot> small = new ArrayList<>();
            List<Ballot> large = new ArrayList<>();
            int shapes = 5 + random.nextInt(20);
            for (int shape = 0; shape < shapes; shape++) {
                List<Candidate> pool = new ArrayList<>(candidates);
                Collections.shuffle(pool, random);
                int ranked = 1 + random.nextInt(pool.size());
                List<Set<Candidate>> ranks = new ArrayList<>();
                for (int i = 0; i < ranked; i++) {
                    // About one ranking in five ties a candidate with the one before.
                    if (i > 0 && random.nextInt(5) == 0) {
                        Set<Candidate> tied = new HashSet<>(ranks.removeLast());
                        tied.add(pool.get(i));
                        ranks.add(Set.copyOf(tied));
                    } else {
                        ranks.add(Set.of(pool.get(i)));
                    }
                }
                BigInteger count = BigInteger.valueOf(1 + random.nextInt(50));
                small.add(new Ballot(ranks, count));
                large.add(new Ballot(ranks, count.multiply(SCALE)));
            }
            return new Election(candidates, small, large);
        }
    }

    private static void assertScaled(Map<Candidate, Map<Candidate, BigInteger>> large,
            Map<Candidate, Map<Candidate, BigInteger>> small, String context) {
        assertThat(large.keySet()).as(context).containsExactlyElementsOf(small.keySet());
        for (Map.Entry<Candidate, Map<Candidate, BigInteger>> row : small.entrySet()) {
            Map<Candidate, BigInteger> largeRow = large.get(row.getKey());
            assertThat(largeRow.keySet()).as(context).containsExactlyElementsOf(row.getValue().keySet());
            for (Map.Entry<Candidate, BigInteger> cell : row.getValue().entrySet()) {
                assertThat(largeRow.get(cell.getKey())).as(context + ", " + row.getKey() + " over " + cell.getKey())
                        .isEqualTo(cell.getValue().multiply(SCALE));
            }
        }
    }

    private static void assertScaled(SchulzeResult large, SchulzeResult small, String context) {
        assertScaled(large.pairwiseWins(), small.pairwiseWins(), context + ", pairwise");
        assertScaled(large.strongestPaths(), small.strongestPaths(), context + ", paths");
        assertThat(large.ranking()).as(context + ", ranking").isEqualTo(small.ranking());
    }

    @Test
    void theSchulzeMethodsLongAndBigIntegerMatricesAgree() {
        Random random = new Random(20261008);
        for (int election = 0; election < 200; election++) {
            Election e = Election.random(random, 3, 8);
            String context = "election " + election;
            assertScaled(PairwiseMatrix.compute(e.candidates(), e.large()),
                    PairwiseMatrix.compute(e.candidates(), e.small()), context);
            assertScaled(SchulzeMethod.count(e.candidates(), e.large()),
                    SchulzeMethod.count(e.candidates(), e.small()), context);
        }
    }

    @Test
    void schulzeStvsLongAndBigIntegerCountsAgree() {
        Random random = new Random(20261009);
        int supports = 0;
        for (int election = 0; election < 100; election++) {
            Election e = Election.random(random, 3, 7);
            int seats = 1 + random.nextInt(Math.min(3, e.candidates().size() - 1));
            SchulzeStvResult small = SchulzeStvCounter.count(e.candidates(), e.small(), seats);
            SchulzeStvResult large = SchulzeStvCounter.count(e.candidates(), e.large(), seats);

            String context = "election " + election;
            assertThat(large.supports()).as(context).hasSameSizeAs(small.supports());
            for (int i = 0; i < small.supports().size(); i++) {
                SchulzeStvResult.Support want = small.supports().get(i);
                SchulzeStvResult.Support got = large.supports().get(i);
                String support = context + ", " + want.committee() + " against " + want.against();
                assertThat(got.committee()).as(support).isEqualTo(want.committee());
                assertThat(got.against()).as(support).isEqualTo(want.against());
                assertThat(got.support()).as(support).isEqualTo(want.support().multiply(SCALE_FRACTION));
            }
            assertThat(large.potentialWinners()).as(context).isEqualTo(small.potentialWinners());
            assertThat(large.winners()).as(context).isEqualTo(small.winners());
            assertScaled(large.candidateRanking(), small.candidateRanking(), context);
            supports += small.supports().size();
        }
        assertThat(supports).as("supports compared").isGreaterThan(3500);
    }

    @Test
    void theProportionalRankingsLongAndBigIntegerCountsAgree() {
        Random random = new Random(20261010);
        for (int election = 0; election < 100; election++) {
            Election e = Election.random(random, 3, 6);
            SchulzeProportionalResult small = SchulzeProportionalRanking.count(e.candidates(), e.small());
            SchulzeProportionalResult large = SchulzeProportionalRanking.count(e.candidates(), e.large());

            String context = "election " + election;
            assertThat(large.ranking()).as(context).isEqualTo(small.ranking());
            assertThat(large.places()).as(context).hasSameSizeAs(small.places());
            for (int i = 0; i < small.places().size(); i++) {
                SchulzeProportionalResult.Place want = small.places().get(i);
                SchulzeProportionalResult.Place got = large.places().get(i);
                String place = context + ", place " + want.place();
                assertThat(got.candidate()).as(place).isEqualTo(want.candidate());
                assertThat(got.potentialWinners()).as(place).isEqualTo(want.potentialWinners());
                assertThat(got.comparisons().keySet()).as(place).containsExactlyElementsOf(want.comparisons().keySet());
                for (Map.Entry<Candidate, Map<Candidate, Fraction>> row : want.comparisons().entrySet()) {
                    Map<Candidate, Fraction> gotRow = got.comparisons().get(row.getKey());
                    assertThat(gotRow.keySet()).as(place).containsExactlyElementsOf(row.getValue().keySet());
                    for (Map.Entry<Candidate, Fraction> cell : row.getValue().entrySet()) {
                        assertThat(gotRow.get(cell.getKey())).as(place + ", " + row.getKey() + " over " + cell.getKey())
                                .isEqualTo(cell.getValue().multiply(SCALE_FRACTION));
                    }
                }
            }
            assertScaled(large.tieBreakRanking(), small.tieBreakRanking(), context + ", tie-break");
        }
    }
}
