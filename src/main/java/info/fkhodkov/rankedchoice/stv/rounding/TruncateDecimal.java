package info.fkhodkov.rankedchoice.stv.rounding;

import info.fkhodkov.rankedchoice.core.util.Fraction;

/** Truncates (rounds toward zero) the transfer value to a fixed number of decimal places. */
public record TruncateDecimal(int decimalPlaces) implements RoundingRule {

    public TruncateDecimal {
        if (decimalPlaces < 0) {
            throw new IllegalArgumentException("decimalPlaces must be non-negative, got: " + decimalPlaces);
        }
    }

    @Override
    public Fraction apply(Fraction value) {
        return value.truncate(decimalPlaces);
    }
}
