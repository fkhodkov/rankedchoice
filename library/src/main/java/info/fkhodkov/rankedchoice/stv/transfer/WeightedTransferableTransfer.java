package info.fkhodkov.rankedchoice.stv.transfer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * Weighted inclusive Gregory over the transferable papers, as the NSW Electoral Commission counts
 * councillor elections (Local Government (General) Regulation 2021, Schedule 5): every paper the
 * elected candidate holds with a further preference moves on at its own value times the surplus
 * fraction, which is the surplus over the transferable votes, the candidate's recorded votes less
 * the value of the papers with no further preference. Those papers stay with the elected
 * candidate. If the transferable votes don't exceed the surplus, each paper moves at the value it
 * arrived at.
 *
 * <p>Each candidate's gain is rounded down to whole votes separately for every parcel the
 * transfer moves, a parcel being the papers that came to the transferring candidate in one
 * transfer at one value (see {@link #roundsGainsPerParcel()}); the rest is lost. Checked against
 * the Commission's full distributions of preferences, which show each parcel's sub-count.
 */
public record WeightedTransferableTransfer() implements TransferMethod {

    public static final WeightedTransferableTransfer INSTANCE = new WeightedTransferableTransfer();

    @Override
    public Map<Integer, Fraction> computeTransferValues(Fraction surplus, List<HeldParcel> parcels,
            RoundingRule roundingRule) {
        Fraction held = Fraction.ZERO;
        for (HeldParcel parcel : parcels) {
            held = held.add(parcel.totalValue());
        }
        return computeTransferValues(surplus, held, parcels, roundingRule);
    }

    @Override
    public Map<Integer, Fraction> computeTransferValues(Fraction surplus, Fraction heldVotes, List<HeldParcel> parcels,
            RoundingRule roundingRule) {
        Fraction transferable = heldVotes;
        for (HeldParcel parcel : parcels) {
            if (!parcel.hasNextPreference()) {
                transferable = transferable.subtract(parcel.totalValue());
            }
        }
        Fraction fraction = transferable.compareTo(surplus) > 0 ? surplus.divide(transferable) : Fraction.ONE;
        Map<Integer, Fraction> result = new LinkedHashMap<>();
        for (HeldParcel parcel : parcels) {
            if (parcel.hasNextPreference()) {
                result.put(parcel.id(), parcel.value().multiply(fraction));
            }
        }
        return result;
    }

    @Override
    public boolean roundsGainsPerStep() {
        return true;
    }

    @Override
    public boolean roundsGainsPerParcel() {
        return true;
    }
}
