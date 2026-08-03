package info.fkhodkov.rankedchoice.stv.transfer;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * A last-parcel-only method identical to {@link LastParcelTransfer} except for where
 * rounding happens: this keeps the shared transfer ratio ({@code surplus / transferable
 * papers}) exact, applying {@code roundingRule} nowhere in this class at all. The rounding
 * instead happens once each destination candidate's own tally is summed (see {@link
 * info.fkhodkov.rankedchoice.stv.StvCounter}), not to the shared per-ballot value.
 *
 * <p>This is the ACT Legislative Assembly's own Hare-Clark convention (Electoral Act 1992
 * (ACT), Schedule 4, clause 1A: "count votes ... BP x TV ... any fraction must be rounded
 * down to 6 decimal places" — BP is a specific destination candidate's ballot count, TV the
 * ratio, so the rounding applies to their product, not to TV on its own), confirmed against
 * real 2020 ACT Legislative Assembly election data (Kurrajong): keeping the ratio exact and
 * rounding only once it's multiplied by a specific destination's ballot count reproduces the
 * official per-candidate figures to the digit; rounding the shared ratio first (as {@link
 * LastParcelTransfer} does, correctly, for ERS97/76 and Tasmania's Hare-Clark) does not.
 */
public record LastParcelDeferredRoundingTransfer() implements TransferMethod {

    public static final LastParcelDeferredRoundingTransfer INSTANCE = new LastParcelDeferredRoundingTransfer();

    @Override
    public Map<Integer, Fraction> computeTransferValues(Fraction surplus, List<HeldParcel> parcels,
            RoundingRule roundingRule) {
        List<HeldParcel> lastParcel = LastParcelSelection.mostRecentArrival(parcels);
        if (lastParcel.isEmpty()) {
            return Map.of();
        }

        Map<Integer, Fraction> result = new LinkedHashMap<>();
        for (HeldParcel parcel : lastParcel) {
            if (!parcel.hasNextPreference()) {
                result.put(parcel.id(), parcel.value());
            }
        }

        List<HeldParcel> transferable = lastParcel.stream().filter(HeldParcel::hasNextPreference).toList();
        BigInteger transferablePapers = transferable.stream()
                .map(HeldParcel::voteCount)
                .reduce(BigInteger.ZERO, BigInteger::add);
        if (transferablePapers.signum() > 0) {
            Fraction ratio = surplus.divide(Fraction.of(transferablePapers));
            for (HeldParcel parcel : transferable) {
                result.put(parcel.id(), parcel.value().multiply(ratio));
            }
        }
        return result;
    }
}
