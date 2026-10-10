package info.fkhodkov.rankedchoice.cambridge;

import info.fkhodkov.rankedchoice.stv.TiedRankingPolicy;
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;

/**
 * The configurable pieces of a Cambridge count (see {@link CambridgeCounter}): the last-resort
 * tie-break, and how to treat tied rankings and short ballots (see {@link
 * info.fkhodkov.rankedchoice.stv.BallotFormality}). Everything else -- the quota, the Cincinnati
 * surplus transfer, the 50-vote first exclusion -- is fixed by the method itself.
 *
 * <p>{@code tieBreak} only settles ties that looking back through earlier rounds can't (see
 * {@link CambridgeCounter}); it's called with the tied candidates and the rounds so far.
 * {@link info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak} matches OpenSTV's
 * {@code -t index}; OpenSTV's own default is to draw lots, which {@link
 * info.fkhodkov.rankedchoice.stv.tiebreak.SeededRandomTieBreak} can stand in for.
 *
 * <p>{@link TiedRankingPolicy#SPLIT} isn't supported -- ballots move whole under this method,
 * so there's nothing to split them with. The default is {@link TiedRankingPolicy#REJECT}.
 */
public record CambridgeRuleset(TieBreakRule tieBreak, TiedRankingPolicy tiedRankings, int minimumPreferences) {

    public CambridgeRuleset {
        if (minimumPreferences < 0) {
            throw new IllegalArgumentException("minimumPreferences must not be negative, got: " + minimumPreferences);
        }
    }

    public CambridgeRuleset(TieBreakRule tieBreak) {
        this(tieBreak, TiedRankingPolicy.REJECT, 0);
    }
}
