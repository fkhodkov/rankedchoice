package info.fkhodkov.rankedchoice.stv.quota;

import java.math.BigInteger;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * The Imperiali quota: {@code floor(totalValidVotes / (seats + 2))}. Smaller than the Droop
 * quota, which lets seats fill faster but — unlike Droop — doesn't mathematically guarantee
 * no more candidates can reach quota than there are seats. Used historically in a small
 * number of jurisdictions; rarely a good default choice.
 */
public record ImperialiQuota() implements Quota {

    public static final ImperialiQuota INSTANCE = new ImperialiQuota();

    @Override
    public Fraction compute(BigInteger totalValidVotes, int seats) {
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive, got: " + seats);
        }
        return Fraction.of(totalValidVotes.divide(BigInteger.valueOf(seats + 2L)));
    }
}
