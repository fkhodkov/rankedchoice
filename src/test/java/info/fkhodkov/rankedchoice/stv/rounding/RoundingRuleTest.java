package info.fkhodkov.rankedchoice.stv.rounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;

class RoundingRuleTest {

    @Test
    void exactKeepsTheValueUnchanged() {
        assertThat(Exact.INSTANCE.apply(Fraction.of(10, 3))).isEqualTo(Fraction.of(10, 3));
    }

    @Test
    void truncateDecimalRoundsTowardZero() {
        // 12/33 = 0.363636... -> truncated to 5 places is 0.36363
        assertThat(new TruncateDecimal(5).apply(Fraction.of(12, 33))).isEqualTo(Fraction.of(36363, 100000));
    }

    @Test
    void truncateDecimalRejectsNegativePlaces() {
        assertThatThrownBy(() -> new TruncateDecimal(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
