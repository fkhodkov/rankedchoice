package info.fkhodkov.rankedchoice.stv.transfer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * The simple/inclusive Gregory method: every parcel the elected candidate currently holds
 * (regardless of when it arrived) is transferred using one ratio, {@code surplus /
 * candidateVotes}, rounded once for the whole candidate and then multiplied by each parcel's
 * own value. Unlike the Weighted Inclusive Gregory Method ({@link WigmTransfer}), parcels
 * that arrived at different prior values don't each get their own separately-rounded
 * transfer value — only WIGM's per-parcel rounding can produce a different result.
 */
public record FractionalTransfer() implements TransferMethod {

    public static final FractionalTransfer INSTANCE = new FractionalTransfer();

    @Override
    public Map<Integer, Fraction> computeTransferValues(Fraction surplus, List<HeldParcel> parcels,
            RoundingRule roundingRule) {
        Fraction candidateVotes = HeldParcel.totalOf(parcels);
        if (candidateVotes.signum() == 0) {
            return Map.of();
        }
        Fraction ratio = roundingRule.apply(surplus.divide(candidateVotes));
        Map<Integer, Fraction> result = new LinkedHashMap<>();
        for (HeldParcel parcel : parcels) {
            result.put(parcel.id(), parcel.value().multiply(ratio));
        }
        return result;
    }
}
