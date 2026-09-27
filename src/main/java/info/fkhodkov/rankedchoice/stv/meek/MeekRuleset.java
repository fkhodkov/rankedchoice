package info.fkhodkov.rankedchoice.stv.meek;

import info.fkhodkov.rankedchoice.stv.TiedRankingPolicy;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;

/**
 * The classic-STV building blocks that still apply to a Meek's-method count: how far to
 * round each round's reported figures, how to break a tie, and how to treat tied rankings
 * and short ballots (see {@link info.fkhodkov.rankedchoice.stv.BallotFormality}). Quota and
 * transfer method aren't configurable here — Meek's method fixes both itself: the quota is
 * always the recomputed {@code (votes - excess) / (seats + 1)} (see {@link MeekCounter}), and
 * "transfer" is the iterative keep-factor recalculation, not a pluggable strategy.
 *
 * <p>Unlike classic STV, Meek's method splits tied rankings under every configuration (see
 * {@link MeekCounter}), so {@link TiedRankingPolicy#SPLIT} is the default.
 */
public record MeekRuleset(
        RoundingRule roundingRule, TieBreakRule tieBreak, TiedRankingPolicy tiedRankings, int minimumPreferences) {

    public MeekRuleset {
        if (minimumPreferences < 0) {
            throw new IllegalArgumentException("minimumPreferences must not be negative, got: " + minimumPreferences);
        }
    }

    public MeekRuleset(RoundingRule roundingRule, TieBreakRule tieBreak) {
        this(roundingRule, tieBreak, TiedRankingPolicy.SPLIT, 0);
    }
}
