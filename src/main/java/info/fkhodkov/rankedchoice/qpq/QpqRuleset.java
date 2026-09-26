package info.fkhodkov.rankedchoice.qpq;

import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;

/**
 * The one configurable piece of a QPQ count: how to break a tie for the highest or lowest
 * quotient. Everything else in Woodall's rule is fully self-contained — quota and quotient
 * are both derived formulas, not pluggable strategies (see {@link QpqCounter}) — and there's
 * no rounding rule at all: every quotient and quota here is this project's exact {@link
 * info.fkhodkov.rankedchoice.core.util.Fraction}, with no numeric tolerance needed anywhere,
 * unlike {@link info.fkhodkov.rankedchoice.stv.meek.MeekCounter} (whose keep-factor
 * convergence has no exact value to reach).
 *
 * <p>Reuses the classic-STV {@link TieBreakRule} interface for consistency with the rest of
 * this project's config parsing, but {@link QpqCounter} always invokes it with an
 * <em>empty</em> prior-stages history: QPQ's own round shape ({@link QpqRoundRecord}) isn't
 * the classic-STV {@link info.fkhodkov.rankedchoice.stv.RoundRecord} the history-dependent
 * rules key off, and Woodall's own reference implementations (droop, OpenSTV) don't define
 * any tie-break procedure of their own either — both just "break a tie by lot." {@link
 * info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak} and {@link
 * info.fkhodkov.rankedchoice.stv.tiebreak.SeededRandomTieBreak} are the only implementations
 * that use that empty history meaningfully; the history-dependent ones ({@code
 * previous-round}, {@code first-preference}) degrade gracefully to whatever they're chained
 * to instead, since an empty history never distinguishes a tie.
 */
public record QpqRuleset(TieBreakRule tieBreak) {
}
