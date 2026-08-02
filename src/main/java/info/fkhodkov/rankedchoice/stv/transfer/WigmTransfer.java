package info.fkhodkov.rankedchoice.stv.transfer;

import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * The Weighted Inclusive Gregory Method (WIGM), as defined in e.g. the Scottish Local
 * Government Elections Order 2007, rule 48(3): for a ballot that currently carries value
 * {@code V}, its new value is {@code round(surplus * V / candidateVotes)}. Critically, the
 * rounding is applied separately to each distinct arrival value a candidate's held ballots
 * carry — not once for the whole candidate, as the simpler {@link FractionalTransfer} does.
 * That only matters once a candidate has received ballots at more than one prior value
 * (because they were themselves a recipient of an earlier transfer); until then the two
 * methods agree exactly.
 */
public record WigmTransfer() implements TransferMethod {

    public static final WigmTransfer INSTANCE = new WigmTransfer();

    @Override
    public Fraction transferValue(Fraction surplus, Fraction candidateVotes, Fraction arrivalValue,
            RoundingRule roundingRule) {
        if (candidateVotes.signum() == 0) {
            return Fraction.ZERO;
        }
        return roundingRule.apply(surplus.multiply(arrivalValue).divide(candidateVotes));
    }
}
