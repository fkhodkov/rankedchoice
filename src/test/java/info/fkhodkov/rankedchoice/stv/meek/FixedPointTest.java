package info.fkhodkov.rankedchoice.stv.meek;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;

class FixedPointTest {

    private final FixedPoint fixed = new FixedPoint(9);

    @Test
    void multiplyAndDivideRoundTheWayTheyAreTold() {
        long third = fixed.divide(fixed.one(), 3 * fixed.one(), false);
        assertThat(third).isEqualTo(333_333_333L);
        assertThat(fixed.divide(fixed.one(), 3 * fixed.one(), true)).isEqualTo(333_333_334L);

        long half = fixed.one() / 2;
        assertThat(fixed.multiply(third, half, false)).isEqualTo(166_666_666L);
        assertThat(fixed.multiply(third, half, true)).isEqualTo(166_666_667L);
        // Exact results aren't nudged up.
        assertThat(fixed.multiply(half, half, true)).isEqualTo(250_000_000L);
    }

    @Test
    void wholeNumberDivisionRoundsDown() {
        assertThat(fixed.divideDown(2 * fixed.one(), 3)).isEqualTo(666_666_666L);
    }

    @Test
    void productsTooLargeForALongAreStillExact() {
        // 5 billion votes at 9 places is 5e18; times a keep factor of 0.5 the raw product is 2.5e27.
        long votes = fixed.of(BigInteger.valueOf(5_000_000_000L));
        long keepFactor = fixed.one() / 2 + 1;

        assertThat(fixed.multiply(votes, keepFactor, false)).isEqualTo(2_500_000_005_000_000_000L);
        assertThat(fixed.divide(votes, 3 * fixed.one(), true)).isEqualTo(1_666_666_666_666_666_667L);
    }

    @Test
    void omegaAndConversion() {
        assertThat(fixed.tenToTheMinus(6)).isEqualTo(1_000L);
        assertThat(fixed.toFraction(1_500_000_000L)).isEqualTo(Fraction.of(3, 2));
    }

    @Test
    void rejectsAPrecisionItCannotHold() {
        assertThatThrownBy(() -> new FixedPoint(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedPoint(16)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesATotalVoteThatWouldLeaveNoHeadroomAndSaysWhatPrecisionWouldFit() {
        new FixedPoint(15).requireRoomFor(BigInteger.valueOf(4_000));

        assertThatThrownBy(() -> new FixedPoint(15).requireRoomFor(BigInteger.valueOf(1_000_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1000000 votes is too many to count at 15 decimal places (at most 4611)")
                .hasMessageContaining("use a precision of 12 or fewer");
    }
}
