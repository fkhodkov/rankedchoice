package info.fkhodkov.rankedchoice.schulze;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/**
 * Validates {@link SchulzeMethod#fromMatrix} against a real Debian General Resolution's own
 * published pairwise tally: GR 2004-002 ("Editorial amendments of the Debian Social Contract on
 * the status of the non-free section"), from the official result page
 * (https://www.debian.org/vote/2004/vote_002.en.html) -- Debian's own devotee voting system
 * computes results with the Schulze method (winning-votes variant, same as this project's).
 *
 * <p>Ballot-level data isn't used here (Debian GR ballots are secret; only the aggregate
 * pairwise tally is published), so this checks the strongest-path/ranking computation directly
 * against real official numbers, skipping this project's own ballot-to-matrix step -- that
 * step is covered separately by {@link SchulzeMethodTest} and {@link PairwiseMatrix}'s own
 * straightforward construction. This election has no cycle (option 2 beats both others
 * directly), so it doesn't exercise the interesting recursive part of the strongest-path
 * computation the way {@link SchulzeMethodTest}'s Wikipedia example does -- it's included
 * anyway as a real-world sanity check of the ranking/winner-determination step specifically.
 */
class SchulzeDebianGr2004002FixtureTest {

    @Test
    void reproducesTheRealResultFromDebiansOwnPublishedTally() {
        Candidate dropNonFree = Candidate.of("Drop non-free");
        Candidate keepNonFree = Candidate.of("Keep non-free");
        Candidate furtherDiscussion = Candidate.of("Further discussion");
        List<Candidate> candidates = List.of(dropNonFree, keepNonFree, furtherDiscussion);

        Map<Candidate, Map<Candidate, BigInteger>> d = new LinkedHashMap<>();
        d.put(dropNonFree, row(keepNonFree, 169, furtherDiscussion, 199));
        d.put(keepNonFree, row(dropNonFree, 303, furtherDiscussion, 304));
        d.put(furtherDiscussion, row(dropNonFree, 260, keepNonFree, 156));

        SchulzeResult result = SchulzeMethod.fromMatrix(candidates, d);

        assertThat(result.winners()).containsExactly(keepNonFree);
        assertThat(result.ranking()).containsExactly(
                java.util.Set.of(keepNonFree), java.util.Set.of(furtherDiscussion), java.util.Set.of(dropNonFree));
    }

    private static Map<Candidate, BigInteger> row(Candidate c1, int v1, Candidate c2, int v2) {
        Map<Candidate, BigInteger> row = new LinkedHashMap<>();
        row.put(c1, BigInteger.valueOf(v1));
        row.put(c2, BigInteger.valueOf(v2));
        return row;
    }
}
