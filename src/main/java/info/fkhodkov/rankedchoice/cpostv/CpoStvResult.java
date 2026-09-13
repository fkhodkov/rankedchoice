package info.fkhodkov.rankedchoice.cpostv;

import java.util.List;
import java.util.Map;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * The outcome of a CPO-STV count: every possible {@code seats}-sized outcome's score against
 * every other, and the resulting ranking from most to least preferred. As with {@link
 * info.fkhodkov.rankedchoice.schulze.SchulzeResult}, {@code ranking} groups outcomes that tied
 * — scored exactly level against each other in both directions — together in one {@link Set};
 * a genuine, unresolved tie for the win is a {@code ranking.getFirst()} of size greater than 1.
 */
public record CpoStvResult(
        Map<Set<Candidate>, Map<Set<Candidate>, Fraction>> outcomeScores,
        List<Set<Set<Candidate>>> ranking
) {
    /** The winning outcome(s) — more than one only when the count ends in a genuine tie. */
    public Set<Set<Candidate>> winners() {
        return ranking.getFirst();
    }
}
