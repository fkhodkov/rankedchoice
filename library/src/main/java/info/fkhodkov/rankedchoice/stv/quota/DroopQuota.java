package info.fkhodkov.rankedchoice.stv.quota;

import java.math.BigInteger;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * The Droop quota: {@code floor(totalValidVotes / (seats + 1)) + 1} — the smallest number of
 * votes that cannot be reached by more candidates than there are seats. Used by Ireland,
 * Scotland, Australia, and most modern STV rulesets.
 */
public record DroopQuota() implements Quota {

    public static final DroopQuota INSTANCE = new DroopQuota();

    @Override
    public Fraction compute(BigInteger totalValidVotes, int seats) {
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive, got: " + seats);
        }
        BigInteger quota = totalValidVotes.divide(BigInteger.valueOf(seats + 1L)).add(BigInteger.ONE);
        return Fraction.of(quota);
    }
}
