package info.fkhodkov.rankedchoice.stv.meek;

/**
 * How an elected candidate's keep factor applies to the weight of a ballot reaching them — the
 * one thing Meek's and Warren's methods do differently (as in droop's {@code rules/meek.py},
 * which counts both with the same loop). Everything else — the quota, keep-factor updates,
 * iteration, and exclusion — is shared (see {@link MeekCounter}).
 */
public enum KeepRule {
    /**
     * Meek's method: the candidate keeps that fraction of whatever reaches them, {@code weight *
     * keepFactor}, passing on {@code weight * (1 - keepFactor)} (both rounded down).
     */
    MEEK,
    /**
     * Warren's method (C. H. E. Warren, "Counting in STV Elections", <i>Voting matters</i> 1,
     * 1994): the keep factor is an amount, not a fraction — every voter pays each elected
     * candidate the same price per ballot, so the candidate keeps {@code keepFactor} of the
     * ballot, or all of what's left of it if that's less, and the rest passes on.
     */
    WARREN
}
