package info.fkhodkov.rankedchoice.stv.meek;

import java.math.BigInteger;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * Fixed-point decimal arithmetic at a set number of decimal places, reproducing droop's {@code
 * Fixed} value class ({@code jklundell/droop}, {@code values/fixed.py}) operation for operation:
 * a value is a {@code long} holding {@code value * 10^places}, adding and subtracting are exact,
 * dividing by a whole number rounds down, and multiplying or dividing two values rounds down
 * or up as each call says. Nothing is ever negative where it's multiplied or divided here, so
 * rounding down is truncation.
 *
 * <p>Products are computed in {@code long} when they fit and in {@link BigInteger} when they
 * don't, so a large election can't overflow a keep-factor update ({@code keepFactor * quota}
 * can exceed {@code Long.MAX_VALUE} well before any vote total does). Every other value a count
 * holds — a candidate's votes, the quota, the surplus, a lowest vote plus that surplus — is at
 * most twice the total vote, so {@link #requireRoomFor} checks that once, up front.
 */
final class FixedPoint {

    private final int places;
    private final long scale;

    FixedPoint(int places) {
        if (places < 1 || places > 15) {
            throw new IllegalArgumentException("Meek precision must be 1 to 15 decimal places, got: " + places);
        }
        this.places = places;
        this.scale = BigInteger.TEN.pow(places).longValueExact();
    }

    /** 1, the value a hopeful candidate keeps of anything reaching them. */
    long one() {
        return scale;
    }

    /** The smallest representable positive value, 10^-places. */
    long epsilon() {
        return 1;
    }

    /**
     * Refuses a count whose total vote, at this many decimal places, would leave no headroom in
     * a {@code long} — about 4.6 * 10^18 / 10^places votes (4.6 billion at 9 places).
     */
    void requireRoomFor(BigInteger totalVotes) {
        BigInteger limit = BigInteger.valueOf(Long.MAX_VALUE / 2 / scale);
        if (totalVotes.compareTo(limit) > 0) {
            int maxPlaces = places;
            while (maxPlaces > 1 && totalVotes.compareTo(
                    BigInteger.valueOf(Long.MAX_VALUE / 2).divide(BigInteger.TEN.pow(maxPlaces))) > 0) {
                maxPlaces--;
            }
            throw new IllegalArgumentException(totalVotes + " votes is too many to count at " + places
                    + " decimal places (at most " + limit + "); use a precision of " + maxPlaces + " or fewer");
        }
    }

    long of(BigInteger wholeNumber) {
        return wholeNumber.multiply(BigInteger.valueOf(scale)).longValueExact();
    }

    /** {@code a * b}, both fixed-point, rounded down or up to the nearest representable value. */
    long multiply(long a, long b, boolean roundUp) {
        return scaledDivide(a, b, scale, roundUp);
    }

    /** {@code a / b}, both fixed-point, rounded down or up to the nearest representable value. */
    long divide(long a, long b, boolean roundUp) {
        return scaledDivide(a, scale, b, roundUp);
    }

    /** {@code a / n} for a whole number {@code n}, rounded down. */
    long divideDown(long a, long n) {
        return Math.floorDiv(a, n);
    }

    /** {@code 10^-digits}, rounded down — droop's {@code omega}. */
    long tenToTheMinus(int digits) {
        return scale / BigInteger.TEN.pow(digits).longValueExact();
    }

    Fraction toFraction(long value) {
        return Fraction.of(BigInteger.valueOf(value), BigInteger.valueOf(scale));
    }

    int places() {
        return places;
    }

    private static long scaledDivide(long a, long b, long c, boolean roundUp) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        if ((high == 0 && low >= 0) || (high == -1 && low < 0)) {
            long quotient = Math.floorDiv(low, c);
            return roundUp && Math.floorMod(low, c) != 0 ? quotient + 1 : quotient;
        }
        BigInteger[] quotientAndRemainder = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
                .divideAndRemainder(BigInteger.valueOf(c));
        long quotient = quotientAndRemainder[0].longValueExact();
        return roundUp && quotientAndRemainder[1].signum() != 0 ? quotient + 1 : quotient;
    }
}
