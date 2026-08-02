package info.fkhodkov.rankedchoice.stv.transfer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * The "last parcel only" (exclusive Gregory) method used by e.g. ERS97 and Hare-Clark:
 * unlike {@link FractionalTransfer}/{@link WigmTransfer}, only the single most recently
 * arrived parcel is examined for a surplus — ballots the candidate received earlier stay
 * credited to them, unchanged, for good. The transfer ratio is {@code surplus / (that
 * parcel's own total votes)}, not the candidate's overall total.
 *
 * <p>A parcel arriving as one transfer event is assumed to be internally uniform in value —
 * true by construction for a ruleset that only ever uses last-parcel transfers itself, but
 * not guaranteed if it's mixed with an inclusive method elsewhere in the same count. If the
 * identified last parcel does turn out to hold more than one value (multiple {@link
 * HeldParcel} entries sharing the same {@code arrivalParcelId}), each is scaled by its own
 * value against the shared parcel total, which reduces to the same result in the uniform
 * case and degrades gracefully rather than silently miscounting otherwise.
 */
public record LastParcelTransfer() implements TransferMethod {

    public static final LastParcelTransfer INSTANCE = new LastParcelTransfer();

    @Override
    public Map<Integer, Fraction> computeTransferValues(Fraction surplus, List<HeldParcel> parcels,
            RoundingRule roundingRule) {
        if (parcels.isEmpty()) {
            return Map.of();
        }
        int lastArrivalId = parcels.stream().mapToInt(HeldParcel::arrivalParcelId).max().orElseThrow();
        List<HeldParcel> lastParcel = parcels.stream()
                .filter(parcel -> parcel.arrivalParcelId() == lastArrivalId)
                .toList();
        Fraction parcelVotes = HeldParcel.totalOf(lastParcel);
        if (parcelVotes.signum() == 0) {
            return Map.of();
        }
        Fraction ratio = roundingRule.apply(surplus.divide(parcelVotes));
        Map<Integer, Fraction> result = new LinkedHashMap<>();
        for (HeldParcel parcel : lastParcel) {
            result.put(parcel.id(), parcel.value().multiply(ratio));
        }
        return result;
    }
}
