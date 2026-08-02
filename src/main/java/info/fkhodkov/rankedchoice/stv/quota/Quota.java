package info.fkhodkov.rankedchoice.stv.quota;

import java.math.BigInteger;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * Computes the number of votes a candidate must reach to be elected outright, from the
 * total valid vote and the number of seats to fill.
 */
public sealed interface Quota permits DroopQuota {

    Fraction compute(BigInteger totalValidVotes, int seats);
}
