package info.fkhodkov.rankedchoice.stv.meek;

import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;

/**
 * The two classic-STV building blocks that still apply to a Meek's-method count: how far to
 * round each round's reported figures, and how to break a tie. Quota and transfer method
 * aren't configurable here — Meek's method fixes both itself: the quota is always the
 * recomputed {@code (votes - excess) / (seats + 1)} (see {@link MeekCounter}), and "transfer"
 * is the iterative keep-factor recalculation, not a pluggable strategy.
 */
public record MeekRuleset(RoundingRule roundingRule, TieBreakRule tieBreak) {
}
