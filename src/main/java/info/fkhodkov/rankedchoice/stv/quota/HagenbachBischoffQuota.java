package info.fkhodkov.rankedchoice.stv.quota;

import java.math.BigInteger;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * The Hagenbach-Bischoff quota: {@code totalValidVotes / (seats + 1)}, kept as an exact
 * (often non-integer) fraction — this is the unrounded quantity that the Droop quota rounds
 * up from (Droop = floor(this) + 1).
 *
 * <p>Strictly, a candidate should need to exceed this quota — not merely reach it — to be
 * safely elected, which only matters when a tally lands exactly on a fractional quota value
 * (possible once surplus transfers are involved). This engine applies the same "reaches or
 * exceeds quota" rule to every quota type uniformly, so that boundary case is treated as
 * elected here; know this if you need exact fidelity to a jurisdiction using this quota.
 */
public record HagenbachBischoffQuota() implements Quota {

    public static final HagenbachBischoffQuota INSTANCE = new HagenbachBischoffQuota();

    @Override
    public Fraction compute(BigInteger totalValidVotes, int seats) {
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive, got: " + seats);
        }
        return Fraction.of(totalValidVotes, BigInteger.valueOf(seats + 1L));
    }
}
