package info.fkhodkov.rankedchoice.stv.transfer;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * Unweighted inclusive Gregory, the Australian Senate's surplus transfer (Commonwealth Electoral
 * Act 1918, s 273): the transfer value is the surplus divided by the number of ballot papers the
 * elected candidate holds, every one of them, and each paper moves on at that value, whatever
 * value it arrived at. Papers with no further preference exhaust at it.
 *
 * <p>The value itself is exact; the Act rounds the votes each candidate receives from a transfer
 * down to a whole number, which the counter does per transfer step (see {@link
 * #roundsGainsPerStep()}), the rest going to "loss by fraction". Checked against the AEC's
 * official distribution of preferences for Tasmania's 2019 Senate election.
 */
public record UnweightedInclusiveTransfer() implements TransferMethod {

    public static final UnweightedInclusiveTransfer INSTANCE = new UnweightedInclusiveTransfer();

    @Override
    public Map<Integer, Fraction> computeTransferValues(Fraction surplus, List<HeldParcel> parcels,
            RoundingRule roundingRule) {
        BigInteger papers = parcels.stream().map(HeldParcel::voteCount).reduce(BigInteger.ZERO, BigInteger::add);
        Map<Integer, Fraction> result = new LinkedHashMap<>();
        if (papers.signum() > 0) {
            Fraction transferValue = surplus.divide(Fraction.of(papers));
            for (HeldParcel parcel : parcels) {
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
