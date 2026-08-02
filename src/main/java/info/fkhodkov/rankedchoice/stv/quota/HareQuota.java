package info.fkhodkov.rankedchoice.stv.quota;

import java.math.BigInteger;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * The Hare quota: {@code totalValidVotes / seats}, kept as an exact (often non-integer)
 * fraction. Larger than the Droop quota, so fewer candidates can reach it outright on first
 * preferences alone; more seats tend to be decided by transfers.
 */
public record HareQuota() implements Quota {

    public static final HareQuota INSTANCE = new HareQuota();

    @Override
    public Fraction compute(BigInteger totalValidVotes, int seats) {
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive, got: " + seats);
        }
        return Fraction.of(totalValidVotes, BigInteger.valueOf(seats));
    }
}
