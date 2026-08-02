package info.fkhodkov.rankedchoice.stv.transfer;

import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * Computes the transfer value applied to every ballot an elected candidate currently holds,
 * when their surplus is distributed to continuing candidates.
 */
public sealed interface TransferMethod permits FractionalTransfer {

    Fraction surplusTransferValue(Fraction surplus, Fraction candidateVotes);
}
