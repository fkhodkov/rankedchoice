package info.fkhodkov.rankedchoice.schulze;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/**
 * The outcome of a Schulze count: the pairwise preference matrix {@code d}, the strongest-path
 * ("beatpath") matrix {@code p} derived from it, and the resulting ranking from most to least
 * preferred. {@code ranking} groups candidates that tied — {@code p[a][b] == p[b][a]} for
 * every pairing within the group — together in one {@link Set}; a genuine, unresolved tie for
 * the win is a {@code ranking.getFirst()} of size greater than 1.
 */
public record SchulzeResult(
        Map<Candidate, Map<Candidate, BigInteger>> pairwiseWins,
        Map<Candidate, Map<Candidate, BigInteger>> strongestPaths,
        List<Set<Candidate>> ranking
) {
    /** The most-preferred group — more than one candidate only when the count ends in a genuine tie. */
    public Set<Candidate> winners() {
        return ranking.getFirst();
    }
}
