package info.fkhodkov.rankedchoice.stv.transfer;

import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * Computes the new value for a single ballot when an elected candidate's surplus is
 * distributed, given the value that ballot currently carries ({@code arrivalValue} — 1 for
 * an original first-preference ballot, or a fraction if it arrived via an earlier transfer).
 * Implementations decide for themselves at what point in their own formula
 * {@code roundingRule} gets applied — that choice is exactly what distinguishes
 * {@link FractionalTransfer} from {@link WigmTransfer}: the former rounds one ratio for the
 * whole candidate and multiplies every ballot's arrival value by it, while the latter rounds
 * separately for each distinct arrival value a candidate's ballots carry.
 */
public sealed interface TransferMethod permits FractionalTransfer, WigmTransfer {

    Fraction transferValue(Fraction surplus, Fraction candidateVotes, Fraction arrivalValue, RoundingRule roundingRule);
}
