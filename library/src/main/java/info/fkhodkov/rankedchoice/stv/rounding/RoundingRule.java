package info.fkhodkov.rankedchoice.stv.rounding;

import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * Applied to a transfer value before it's used to scale ballots. Independent of quota and
 * transfer method choice — real rulesets fix this separately (e.g. the Scottish rules
 * truncate transfer values to 5 decimal places).
 */
public sealed interface RoundingRule permits Exact, TruncateDecimal {

    Fraction apply(Fraction value);
}
