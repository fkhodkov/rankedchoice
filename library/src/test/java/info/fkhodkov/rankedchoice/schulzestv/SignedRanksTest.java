package info.fkhodkov.rankedchoice.schulzestv;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * {@link SignedRanks} keeps the exact order of every pair of values, ties included, and a
 * value's key negated is its negative's: on random fractions with many repeats, values a
 * hair apart (closer than a {@code double} tells), denominators past a {@code double}'s range,
 * and zero.
 */
class SignedRanksTest {

    @Test
    void keysCompareAsTheFractionsDo() {
        Random random = new Random(20261029);
        for (int trial = 0; trial < 40; trial++) {
            Fraction[][] values = new Fraction[1 + random.nextInt(8)][];
            for (int r = 0; r < values.length; r++) {
                values[r] = new Fraction[random.nextInt(30)];
                for (int i = 0; i < values[r].length; i++) {
                    values[r][i] = value(random);
                }
            }
            int[][] keys = SignedRanks.of(values);
            for (int r = 0; r < values.length; r++) {
                assertThat(keys[r]).hasSize(values[r].length);
                for (int i = 0; i < values[r].length; i++) {
                    assertThat(Integer.signum(keys[r][i])).isEqualTo(values[r][i].signum());
                    for (int q = 0; q < values.length; q++) {
                        for (int j = 0; j < values[q].length; j++) {
                            assertThat(Integer.signum(Integer.compare(keys[r][i], keys[q][j])))
                                    .as("trial %d: %s against %s", trial, values[r][i], values[q][j])
                                    .isEqualTo(values[r][i].compareTo(values[q][j]));
                            assertThat(keys[r][i] == -keys[q][j])
                                    .isEqualTo(values[r][i].compareTo(values[q][j].negate()) == 0);
                        }
                    }
                }
            }
        }
    }

    private static Fraction value(Random random) {
        int sign = random.nextBoolean() ? 1 : -1;
        return switch (random.nextInt(5)) {
            case 0 -> Fraction.ZERO;
            case 1 -> Fraction.of(sign * random.nextInt(4), 1 + random.nextInt(3)); // repeats
            case 2 -> { // within 2^-200 of 1/3
                BigInteger huge = BigInteger.ONE.shiftLeft(200);
                yield Fraction.of(huge.add(BigInteger.valueOf(sign * random.nextInt(3))),
                        huge.multiply(BigInteger.valueOf(3)));
            }
            case 3 -> { // numerator and denominator past 2^1100
                BigInteger huge = BigInteger.ONE.shiftLeft(1100).add(BigInteger.valueOf(random.nextInt(1000)));
                yield Fraction.of(huge.multiply(BigInteger.valueOf(sign * (1 + random.nextInt(5)))),
                        huge.add(BigInteger.ONE));
            }
            default -> Fraction.of(sign * (long) random.nextInt(1_000_000), 1 + random.nextInt(1000));
        };
    }
}
