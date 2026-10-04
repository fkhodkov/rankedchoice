package info.fkhodkov.rankedchoice.stv.transfer;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * Unweighted exclusive Gregory, Tasmania's House of Assembly surplus transfer (Electoral Act 2004
 * (Tas), Schedule 4, clauses 6 and 8(4)): only the last parcel the elected candidate received is
 * re-examined (all of their first preferences, if elected on those), and the transfer value is
 * the surplus divided by the number of ballot papers in it, every one of them, those with no
 * further preference included. Each paper moves on at that value, whatever value it arrived at;
 * papers with no further preference exhaust at it.
 *
 * <p>The transfer value is truncated to {@code transferValuePlaces} decimal places (the Tasmanian
 * Electoral Commission works to 6), and each candidate's gain from a transfer is then rounded
 * down to a whole number, "disregarding any fractional remainder", which the counter does per
 * transfer step with the ruleset's rounding (see {@link #roundsGainsPerStep()}). What neither
 * passes on is loss by fraction.
 *
 * <p>Unlike {@link LastParcelTransfer} (ERS97's exclusive Gregory), the divisor counts the papers
 * with no further preference too, and the value is rounded to its own precision rather than the
 * ruleset's.
 */
public record UnweightedExclusiveTransfer(int transferValuePlaces) implements TransferMethod {

    public UnweightedExclusiveTransfer {
        if (transferValuePlaces < 0) {
            throw new IllegalArgumentException("transferValuePlaces must be non-negative, got: " + transferValuePlaces);
        }
    }

    @Override
    public Map<Integer, Fraction> computeTransferValues(Fraction surplus, List<HeldParcel> parcels,
            RoundingRule roundingRule) {
        List<HeldParcel> lastParcel = LastParcelSelection.mostRecentArrival(parcels);
        BigInteger papers = lastParcel.stream().map(HeldParcel::voteCount).reduce(BigInteger.ZERO, BigInteger::add);
        Map<Integer, Fraction> result = new LinkedHashMap<>();
        if (papers.signum() > 0) {
            Fraction transferValue = surplus.divide(Fraction.of(papers)).truncate(transferValuePlaces);
            for (HeldParcel parcel : lastParcel) {
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
