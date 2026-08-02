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

    @Test
    void roundedUpQuotaStaysExactWhenDivisionIsExact() {
        // 60/(2+1) = 20 exactly -> stays 20, per ERS97's "if the result is exact, that is the quota".
        assertThat(new RoundedUpQuota(2).compute(BigInteger.valueOf(60), 2)).isEqualTo(Fraction.of(20));
    }

    @Test
    void roundedUpQuotaRoundsAwayFromZeroWhenNotExact() {
        // 100/(2+1) = 33.333... -> ERS97: "ignore the remainder, and add 0.01" -> 33.34.
        assertThat(new RoundedUpQuota(2).compute(BigInteger.valueOf(100), 2)).isEqualTo(Fraction.of(3334, 100));
    }

    @Test
    void roundedUpQuotaRejectsNegativePlaces() {
        assertThatThrownBy(() -> new RoundedUpQuota(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
