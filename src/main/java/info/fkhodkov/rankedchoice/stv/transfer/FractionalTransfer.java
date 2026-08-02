package info.fkhodkov.rankedchoice.stv.transfer;

import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * The simple/inclusive Gregory method: every ballot the elected candidate currently holds
 * (regardless of when it arrived) is transferred at a single uniform value,
 * {@code surplus / candidateVotes}. Unlike the Weighted Inclusive Gregory Method, this does
 * not account for ballots arriving at different pre-existing values — it treats all of the
 * candidate's held ballots as equally weighted for the purpose of this transfer.
 */
public record FractionalTransfer() implements TransferMethod {

    public static final FractionalTransfer INSTANCE = new FractionalTransfer();

    @Override
    public Fraction surplusTransferValue(Fraction surplus, Fraction candidateVotes) {
        if (candidateVotes.signum() == 0) {
            return Fraction.ZERO;
        }
        return surplus.divide(candidateVotes);
    }
}
