package info.fkhodkov.rankedchoice.stv.transfer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * Weighted inclusive Gregory over every paper, as Western Australia counts its Legislative Council
 * (Electoral Act 1907, Schedule 1, clauses 4 and 5): the surplus fraction is the surplus over the
 * votes the elected candidate is recorded as holding, and every paper they hold moves on at its
 * own value times that fraction (its "continued transfer value"); papers with no further
 * preference exhaust at it.
 *
 * <p>Each candidate's gain from each parcel is rounded down to whole votes, "disregarding any
 * fraction" (see {@link #roundsGainsPerParcel()}), as OpenTally's WA preset reads the Schedule.
 * Unlike {@link WeightedTransferableTransfer} (NSW), the papers with no further preference count
 * in the divisor and leave the elected candidate.
 */
public record WeightedInclusiveTransfer() implements TransferMethod {

    public static final WeightedInclusiveTransfer INSTANCE = new WeightedInclusiveTransfer();

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
        Fraction fraction = surplus.divide(heldVotes);
        Map<Integer, Fraction> result = new LinkedHashMap<>();
        for (HeldParcel parcel : parcels) {
            result.put(parcel.id(), parcel.value().multiply(fraction));
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
