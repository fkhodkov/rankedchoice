package info.fkhodkov.rankedchoice.stv.rounding;

import info.fkhodkov.rankedchoice.core.util.Fraction;

/** Keeps the transfer value as an exact rational fraction — no rounding at all. */
public record Exact() implements RoundingRule {

    public static final Exact INSTANCE = new Exact();

    @Override
    public Fraction apply(Fraction value) {
        return value;
    }
}
