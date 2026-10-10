package info.fkhodkov.rankedchoice.stv.meek;

import info.fkhodkov.rankedchoice.stv.TiedRankingPolicy;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;

/**
 * The classic-STV building blocks that still apply to a Meek's-method count: how far to
 * round each round's reported figures, how to break a tie, and how to treat tied rankings
 * and short ballots (see {@link info.fkhodkov.rankedchoice.stv.BallotFormality}) — plus
 * {@code precision}, the number of decimal places the count itself works to (see {@link
 * MeekCounter}; 9 by default, droop's default for fixed-point Meek), and {@code keepRule},
 * which makes the count Meek's method or Warren's (see {@link KeepRule}). Quota and transfer
 * method aren't configurable here — both methods fix both themselves: the quota is recomputed
 * from the votes candidates actually hold every iteration, and "transfer" is the iterative
 * keep-factor recalculation, not a pluggable strategy.
 *
 * <p>{@code roundingRule} only shapes the reported figures. Every figure the count computes is
 * already an exact decimal at {@code precision} places, so {@code truncate:N} with N at least
 * {@code precision} (or {@code exact}) reports them unchanged.
 *
 * <p>Unlike classic STV, Meek's method splits tied rankings under every configuration (see
 * {@link MeekCounter}), so {@link TiedRankingPolicy#SPLIT} is the default.
 */
public record MeekRuleset(RoundingRule roundingRule, TieBreakRule tieBreak, TiedRankingPolicy tiedRankings,
        int minimumPreferences, int precision, KeepRule keepRule) {

    public static final int DEFAULT_PRECISION = 9;

    public MeekRuleset {
        if (minimumPreferences < 0) {
            throw new IllegalArgumentException("minimumPreferences must not be negative, got: " + minimumPreferences);
        }
        new FixedPoint(precision); // validates the range
        if (keepRule == null) {
            throw new IllegalArgumentException("keepRule must not be null");
        }
    }

    public MeekRuleset(RoundingRule roundingRule, TieBreakRule tieBreak, TiedRankingPolicy tiedRankings,
            int minimumPreferences, int precision) {
        this(roundingRule, tieBreak, tiedRankings, minimumPreferences, precision, KeepRule.MEEK);
    }

    public MeekRuleset(
            RoundingRule roundingRule, TieBreakRule tieBreak, TiedRankingPolicy tiedRankings, int minimumPreferences) {
        this(roundingRule, tieBreak, tiedRankings, minimumPreferences, DEFAULT_PRECISION);
    }

    public MeekRuleset(RoundingRule roundingRule, TieBreakRule tieBreak) {
        this(roundingRule, tieBreak, TiedRankingPolicy.SPLIT, 0);
    }
}
