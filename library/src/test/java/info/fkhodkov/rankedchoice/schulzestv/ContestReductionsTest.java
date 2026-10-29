package info.fkhodkov.rankedchoice.schulzestv;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Interruption;

/**
 * {@link ContestReductions}, which builds every contest's reduction from its parent's, against
 * {@link SchulzeStvCounter.Reduced#of}, which reduces each contest's ballots afresh: on random
 * elections with ties, short ballots, blank ballots and every ballot ranking every candidate,
 * every contest of every size has the same groups with the same voters.
 */
class ContestReductionsTest {

    @Test
    void everyContestIsReducedAsOneByOne() {
        Random random = new Random(20261029);
        int contests = 0;
        for (int trial = 0; trial < 300; trial++) {
            int n = 2 + random.nextInt(9);
            List<Candidate> candidates = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                candidates.add(Candidate.of("C" + i));
            }
            boolean complete = random.nextInt(4) == 0;
            List<Ballot> ballots = new ArrayList<>();
            int shapes = 1 + random.nextInt(40);
            for (int shape = 0; shape < shapes; shape++) {
                List<Candidate> pool = new ArrayList<>(candidates);
                Collections.shuffle(pool, random);
                int ranked = complete ? n : random.nextInt(n + 1); // 0: a blank ballot
                List<Set<Candidate>> ranks = new ArrayList<>();
                for (int i = 0; i < ranked; i++) {
                    if (i > 0 && random.nextInt(4) == 0) {
                        Set<Candidate> tied = new HashSet<>(ranks.removeLast());
                        tied.add(pool.get(i));
                        ranks.add(Set.copyOf(tied));
                    } else {
                        ranks.add(Set.of(pool.get(i)));
                    }
                }
                ballots.add(new Ballot(ranks, BigInteger.valueOf(1 + random.nextInt(30))));
            }
            SchulzeStvCounter.IndexedBallots indexed = SchulzeStvCounter.IndexedBallots.of(candidates, ballots);
            int size = 2 + random.nextInt(n - 1);
            assertThat(ContestReductions.applies(size, indexed)).isTrue();
            Map<List<Integer>, SchulzeStvCounter.Reduced> built = new ConcurrentHashMap<>();
            ContestReductions.forEach(n, size, indexed, Interruption.watch(),
                    (contest, reduced) -> built.put(Arrays.stream(contest).boxed().toList(), reduced));
            assertThat(built).hasSize(binomial(n, size));
            for (Map.Entry<List<Integer>, SchulzeStvCounter.Reduced> contest : built.entrySet()) {
                int[] members = contest.getKey().stream().mapToInt(Integer::intValue).toArray();
                assertThat(groups(contest.getValue()))
                        .as("trial %d, contest %s", trial, contest.getKey())
                        .isEqualTo(groups(SchulzeStvCounter.Reduced.of(members, indexed)));
                contests++;
            }
        }
        assertThat(contests).isGreaterThan(5000);
    }

    /** Each group's levels with its voters, groups with the same levels merged. */
    private static Map<List<Integer>, BigInteger> groups(SchulzeStvCounter.Reduced reduced) {
        Map<List<Integer>, BigInteger> groups = new HashMap<>();
        for (int g = 0; g < reduced.levels().length; g++) {
            groups.merge(Arrays.stream(reduced.levels()[g]).boxed().toList(), reduced.counts()[g], BigInteger::add);
        }
        return groups;
    }

    private static int binomial(int n, int k) {
        long result = 1;
        for (int i = 0; i < k; i++) {
            result = result * (n - i) / (i + 1);
        }
        return (int) result;
    }
}
