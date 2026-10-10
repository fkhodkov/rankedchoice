package info.fkhodkov.rankedchoice.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class FractionTest {

    @Test
    void reducesToLowestTerms() {
        assertThat(Fraction.of(4, 8)).isEqualTo(Fraction.of(1, 2));
    }

    @Test
    void normalizesNegativeDenominator() {
        Fraction f = Fraction.of(1, -2);
        assertThat(f.numerator()).isEqualTo(BigInteger.valueOf(-1));
        assertThat(f.denominator()).isEqualTo(BigInteger.valueOf(2));
    }

    @Test
    void zeroDenominatorThrows() {
        assertThatThrownBy(() -> Fraction.of(1, 0)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void zeroNormalizesDenominatorToOne() {
        assertThat(Fraction.of(0, 5)).isEqualTo(Fraction.of(0, 1));
    }

    @Test
    void add() {
        assertThat(Fraction.of(1, 3).add(Fraction.of(1, 6))).isEqualTo(Fraction.of(1, 2));
    }

    @Test
    void subtract() {
        assertThat(Fraction.of(1, 2).subtract(Fraction.of(1, 3))).isEqualTo(Fraction.of(1, 6));
    }

    @Test
    void multiply() {
        assertThat(Fraction.of(2, 3).multiply(Fraction.of(3, 4))).isEqualTo(Fraction.of(1, 2));
    }

    @Test
    void divide() {
        assertThat(Fraction.of(2, 3).divide(Fraction.of(4, 9))).isEqualTo(Fraction.of(3, 2));
    }

    @Test
    void divideByZeroThrows() {
        assertThatThrownBy(() -> Fraction.of(1, 2).divide(Fraction.ZERO))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void compareTo() {
        assertThat(Fraction.of(1, 3).compareTo(Fraction.of(1, 2))).isLessThan(0);
        assertThat(Fraction.of(1, 2)).isEqualByComparingTo(Fraction.of(2, 4));
        assertThat(Fraction.of(2, 3).compareTo(Fraction.of(1, 2))).isGreaterThan(0);
    }

    @Test
    void truncateRoundsTowardZero() {
        // 10/3 = 3.3333...
        assertThat(Fraction.of(10, 3).truncate(4)).isEqualTo(Fraction.of(33333, 10000));
    }

    @Test
    void truncateOfExactSurplusTransferValue() {
        // Scottish STV rule: truncate transfer value to 5 decimal places.
        // 37/8000 = 0.004625 exactly (6 decimal digits) -> truncated to 5 places is 0.00462.
        Fraction transferValue = Fraction.of(37, 8000).truncate(5);
        assertThat(transferValue).isEqualTo(Fraction.of(462, 100000));
    }

    @Test
    void roundHalfUp() {
        assertThat(Fraction.of(5, 4).round(0, RoundingMode.HALF_UP)).isEqualTo(Fraction.of(1));
        assertThat(Fraction.of(1, 2).round(0, RoundingMode.HALF_UP)).isEqualTo(Fraction.of(1));
    }

    @Test
    void toStringWholeNumberOmitsDenominator() {
        assertThat(Fraction.of(6, 3)).hasToString("2");
    }

    @Test
    void toStringFractionalKeepsDenominator() {
        assertThat(Fraction.of(1, 3)).hasToString("1/3");
    }

    @Test
    void toDecimalStringOfWholeNumberOmitsDecimalPoint() {
        assertThat(Fraction.of(6, 3).toDecimalString()).isEqualTo("2");
    }

    @Test
    void toDecimalStringIsExactWhenItTerminates() {
        // 5379/50 -- the "ers97" model election's quota -- terminates in 2 decimal places.
        assertThat(Fraction.of(5379, 50).toDecimalString()).isEqualTo("107.58");
    }

    @Test
    void toDecimalStringDropsTrailingZerosButKeepsSignificantOnes() {
        // 1/8 = 0.125 exactly; 3/8 = 0.375 exactly -- neither should be padded or truncated.
        assertThat(Fraction.of(1, 8).toDecimalString()).isEqualTo("0.125");
        assertThat(Fraction.of(3, 8).toDecimalString()).isEqualTo("0.375");
    }

    @Test
    void toDecimalStringRoundsNonTerminatingValuesToTenPlaces() {
        // 1/3 = 0.3333... never terminates -- only reachable via "exact" rounding, since every
        // truncate:N rule this project ships keeps N well under 10.
        assertThat(Fraction.of(1, 3).toDecimalString()).isEqualTo("0.3333333333");
    }
}
