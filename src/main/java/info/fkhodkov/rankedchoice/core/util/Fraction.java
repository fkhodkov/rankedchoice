package info.fkhodkov.rankedchoice.core.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * An exact rational number, always kept in lowest terms with a positive denominator.
 * Used instead of floating point so STV surplus transfers don't accumulate rounding error
 * across many rounds; any jurisdiction-specific rounding is applied explicitly by a
 * {@code RoundingRule}, not silently by this type.
 */
public record Fraction(BigInteger numerator, BigInteger denominator) implements Comparable<Fraction> {

    public static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);
    public static final Fraction ONE = new Fraction(BigInteger.ONE, BigInteger.ONE);

    public Fraction {
        if (denominator.signum() == 0) {
            throw new ArithmeticException("Fraction denominator cannot be zero");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.gcd(denominator);
        if (!gcd.equals(BigInteger.ONE)) {
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
    }

    public static Fraction of(long numerator, long denominator) {
        return new Fraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    public static Fraction of(long value) {
        return new Fraction(BigInteger.valueOf(value), BigInteger.ONE);
    }

    public static Fraction of(BigInteger numerator, BigInteger denominator) {
        return new Fraction(numerator, denominator);
    }

    public static Fraction of(BigInteger value) {
        return new Fraction(value, BigInteger.ONE);
    }

    public Fraction add(Fraction other) {
        return new Fraction(
                numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public Fraction subtract(Fraction other) {
        return new Fraction(
                numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public Fraction multiply(Fraction other) {
        return new Fraction(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    public Fraction divide(Fraction other) {
        if (other.numerator.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return new Fraction(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }

    public Fraction negate() {
        return new Fraction(numerator.negate(), denominator);
    }

    public int signum() {
        return numerator.signum();
    }

    /** Truncates (rounds toward zero) to the given number of decimal places. */
    public Fraction truncate(int decimalPlaces) {
        return round(decimalPlaces, RoundingMode.DOWN);
    }

    public Fraction round(int decimalPlaces, RoundingMode mode) {
        // Extra guard digits so the intermediate division doesn't itself lose precision
        // before the real rounding step below is applied.
        BigDecimal exact = new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), decimalPlaces + 32, RoundingMode.HALF_UP);
        BigDecimal scaled = exact.setScale(decimalPlaces, mode);
        return new Fraction(scaled.unscaledValue(), BigInteger.TEN.pow(decimalPlaces));
    }

    public BigDecimal toBigDecimal(int scale, RoundingMode mode) {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), scale, mode);
    }

    @Override
    public int compareTo(Fraction other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public String toString() {
        return denominator.equals(BigInteger.ONE) ? numerator.toString() : numerator + "/" + denominator;
    }
}
