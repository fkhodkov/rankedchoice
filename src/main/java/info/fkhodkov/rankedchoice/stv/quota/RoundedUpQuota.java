package info.fkhodkov.rankedchoice.stv.quota;

import java.math.BigInteger;
import java.math.RoundingMode;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * A Droop-style quota computed to a fixed number of decimal places, rounded <em>up</em> if
 * the division isn't exact — used by ERS97 rather than the traditional integer Droop quota
 * (which is equivalent to rounding up to zero decimal places). ERS97's own wording: "Take
 * the division to two decimal places. If the result is exact that is the quota. Otherwise
 * ignore the remainder, and add 0.01."
 */
public record RoundedUpQuota(int decimalPlaces) implements Quota {

    public RoundedUpQuota {
        if (decimalPlaces < 0) {
            throw new IllegalArgumentException("decimalPlaces must be non-negative, got: " + decimalPlaces);
        }
    }

    @Override
    public Fraction compute(BigInteger totalValidVotes, int seats) {
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive, got: " + seats);
        }
        Fraction raw = Fraction.of(totalValidVotes, BigInteger.valueOf(seats + 1L));
        return raw.round(decimalPlaces, RoundingMode.CEILING);
    }
}
