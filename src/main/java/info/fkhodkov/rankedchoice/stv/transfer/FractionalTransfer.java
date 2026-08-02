package info.fkhodkov.rankedchoice.stv.transfer;

import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * The simple/inclusive Gregory method: every ballot the elected candidate currently holds
 * (regardless of when it arrived) is transferred using one ratio, {@code surplus /
 * candidateVotes}, rounded once for the whole candidate and then multiplied by each ballot's
 * own arrival value. Unlike the Weighted Inclusive Gregory Method ({@link WigmTransfer}),
 * ballots that arrived at different prior values don't each get their own separately-rounded
 * transfer value — only WIGM's per-arrival-value rounding can produce a different result.
 */
public record FractionalTransfer() implements TransferMethod {

    public static final FractionalTransfer INSTANCE = new FractionalTransfer();

    @Override
    public Fraction transferValue(Fraction surplus, Fraction candidateVotes, Fraction arrivalValue,
            RoundingRule roundingRule) {
        if (candidateVotes.signum() == 0) {
            return Fraction.ZERO;
        }
        Fraction ratio = roundingRule.apply(surplus.divide(candidateVotes));
        return arrivalValue.multiply(ratio);
    }
}
