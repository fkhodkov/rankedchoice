package info.fkhodkov.rankedchoice.stv.transfer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * Fractional transfer rounded twice, as Portland, Oregon counts its multi-seat Council contests
 * (City Code 2.08.030 C.2.b(1)): the surplus fraction, the surplus over the candidate's votes, is
 * truncated, and each ballot's new transfer value is its current value times that fraction,
 * truncated again, both with the ruleset's rounding (Portland's: 4 decimal places).
 *
 * <p>Unlike {@link FractionalTransfer}, which rounds only the fraction, and {@link WigmTransfer},
 * which rounds only each product, it rounds both; they all agree on ballots still worth 1.
 */
public record TruncatedFractionalTransfer() implements TransferMethod {

    public static final TruncatedFractionalTransfer INSTANCE = new TruncatedFractionalTransfer();

    @Override
    public Map<Integer, Fraction> computeTransferValues(Fraction surplus, List<HeldParcel> parcels,
            RoundingRule roundingRule) {
        Fraction candidateVotes = HeldParcel.totalOf(parcels);
        if (candidateVotes.signum() == 0) {
            return Map.of();
        }
        Fraction fraction = roundingRule.apply(surplus.divide(candidateVotes));
        Map<Integer, Fraction> result = new LinkedHashMap<>();
        for (HeldParcel parcel : parcels) {
            result.put(parcel.id(), roundingRule.apply(parcel.value().multiply(fraction)));
        }
        return result;
    }
}
