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
 * instead applies to each candidate's gain from each transfer step, a surplus or one value
 * group of an exclusion (see {@link info.fkhodkov.rankedchoice.stv.StvCounter}), not to the
 * shared per-ballot value; tallies and exhausted votes are sums of those rounded gains.
 *
 * <p>This is the ACT Legislative Assembly's own Hare-Clark convention (Electoral Act 1992
 * (ACT), Schedule 4, clause 1A: "count votes ... BP x TV ... any fraction must be rounded
 * down to 6 decimal places" — BP is a specific destination candidate's ballot count, TV the
 * ratio, so the rounding applies to their product, not to TV on its own), confirmed against
 * all five electorates of the 2020 ACT Legislative Assembly election: rounding each step's
 * gains this way reproduces every official count to the digit, votes exhausted and loss by
 * fraction included; rounding the shared ratio first (as {@link LastParcelTransfer} does,
 * correctly, for ERS97/76 and Tasmania's Hare-Clark) does not.
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

        // Papers with no further preference stay with the elected candidate (see LastParcelTransfer).
        Map<Integer, Fraction> result = new LinkedHashMap<>();
        List<HeldParcel> transferable = lastParcel.stream().filter(HeldParcel::hasNextPreference).toList();
        BigInteger transferablePapers = transferable.stream()
                .map(HeldParcel::voteCount)
                .reduce(BigInteger.ZERO, BigInteger::add);
        if (transferablePapers.signum() > 0 && surplus.compareTo(LastParcelTransfer.totalValue(transferable)) >= 0) {
            // A paper never gains value (see LastParcelTransfer).
            for (HeldParcel parcel : transferable) {
                result.put(parcel.id(), parcel.value());
            }
        } else if (transferablePapers.signum() > 0) {
            // A value per paper (votes = BP x TV), as in LastParcelTransfer.
            Fraction transferValue = surplus.divide(Fraction.of(transferablePapers));
            for (HeldParcel parcel : transferable) {
                result.put(parcel.id(), transferValue);
            }
        }
        return result;
    }

    @Override
    public boolean roundsGainsPerStep() {
        return true;
    }
}
