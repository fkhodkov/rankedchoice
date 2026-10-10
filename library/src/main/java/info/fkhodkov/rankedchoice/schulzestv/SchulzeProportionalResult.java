package info.fkhodkov.rankedchoice.schulzestv;

import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.schulze.SchulzeResult;

/**
 * The outcome of a Schulze proportional ranking (see {@link SchulzeProportionalRanking}).
 *
 * <p>{@code ranking} is the list, first place first. {@code places} says how each place was
 * filled: {@code comparisons} holds H[b][c] = N[the list so far + b; c] for every pair of the
 * candidates still unplaced (empty for the last place, which goes to whoever's left), and
 * {@code potentialWinners} those the single-winner Schulze method on H left unbeaten; with
 * more than one, {@code tieBreakRanking}, the single-winner Schulze ranking of all the
 * candidates, chose among them.
 */
public record SchulzeProportionalResult(
        List<Candidate> ranking,
        List<Place> places,
        SchulzeResult tieBreakRanking
) {
    /** How place {@code place} (1-based) went to {@code candidate}. */
    public record Place(int place, Candidate candidate, Map<Candidate, Map<Candidate, Fraction>> comparisons,
            List<Candidate> potentialWinners) {
    }
}
