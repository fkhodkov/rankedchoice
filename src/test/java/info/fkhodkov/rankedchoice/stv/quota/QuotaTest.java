package info.fkhodkov.rankedchoice.stv.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;

class QuotaTest {

    @Test
    void droopQuota() {
        assertThat(DroopQuota.INSTANCE.compute(BigInteger.valueOf(57), 2)).isEqualTo(Fraction.of(20));
        assertThat(DroopQuota.INSTANCE.compute(BigInteger.valueOf(10), 1)).isEqualTo(Fraction.of(6));
    }

    @Test
    void droopQuotaRejectsNonPositiveSeats() {
        assertThatThrownBy(() -> DroopQuota.INSTANCE.compute(BigInteger.TEN, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hareQuotaKeepsExactFraction() {
        assertThat(HareQuota.INSTANCE.compute(BigInteger.valueOf(100), 3)).isEqualTo(Fraction.of(100, 3));
        assertThat(HareQuota.INSTANCE.compute(BigInteger.valueOf(100), 4)).isEqualTo(Fraction.of(25));
    }

    @Test
    void hagenbachBischoffQuotaIsUnroundedDroopBasis() {
        assertThat(HagenbachBischoffQuota.INSTANCE.compute(BigInteger.valueOf(57), 2)).isEqualTo(Fraction.of(19));
        assertThat(HagenbachBischoffQuota.INSTANCE.compute(BigInteger.valueOf(100), 3)).isEqualTo(Fraction.of(25));
    }

    @Test
    void imperialiQuotaIsSmallerThanDroop() {
        assertThat(ImperialiQuota.INSTANCE.compute(BigInteger.valueOf(100), 3)).isEqualTo(Fraction.of(20));
        assertThat(ImperialiQuota.INSTANCE.compute(BigInteger.valueOf(57), 2)).isEqualTo(Fraction.of(14));
    }
}
