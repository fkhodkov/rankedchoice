package info.fkhodkov.rankedchoice.stv.quota;

import java.math.BigInteger;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * Computes the number of votes a candidate must reach to be elected outright, from the
 * total valid vote and the number of seats to fill. This engine treats every quota
 * uniformly as a "reach or exceed" threshold; quotas that are strictly defined as
 * unrounded fractions (Hagenbach-Bischoff) note where that simplification applies.
 */
public sealed interface Quota permits DroopQuota, HareQuota, HagenbachBischoffQuota, ImperialiQuota, RoundedUpQuota {

    Fraction compute(BigInteger totalValidVotes, int seats);
}
