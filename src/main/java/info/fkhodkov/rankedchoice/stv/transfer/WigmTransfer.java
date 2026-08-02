package info.fkhodkov.rankedchoice.stv.transfer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * The Weighted Inclusive Gregory Method (WIGM), as defined in e.g. the Scottish Local
 * Government Elections Order 2007, rule 48(3): for a parcel that currently carries value
 * {@code V}, its new value is {@code round(surplus * V / candidateVotes)}. Critically, the
 * rounding is applied separately to each distinct parcel a candidate holds — not once for
 * the whole candidate, as the simpler {@link FractionalTransfer} does. That only matters
 * once a candidate has received ballots at more than one prior value (because they were
 * themselves a recipient of an earlier transfer); until then the two methods agree exactly.
 */
public record WigmTransfer() implements TransferMethod {

    public static final WigmTransfer INSTANCE = new WigmTransfer();

    @Override
    public Map<Integer, Fraction> computeTransferValues(Fraction surplus, List<HeldParcel> parcels,
            RoundingRule roundingRule) {
        Fraction candidateVotes = HeldParcel.totalOf(parcels);
        if (candidateVotes.signum() == 0) {
            return Map.of();
        }
        Map<Integer, Fraction> result = new LinkedHashMap<>();
        for (HeldParcel parcel : parcels) {
            Fraction newValue = roundingRule.apply(surplus.multiply(parcel.value()).divide(candidateVotes));
            result.put(parcel.id(), newValue);
        }
        return result;
    }
}
