package info.fkhodkov.rankedchoice.stv.transfer;

import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * Decides how an elected candidate's surplus is distributed across the parcels of ballots
 * they currently hold. Returns a map from {@link HeldParcel#id} to that parcel's new value;
 * a parcel absent from the returned map is left untouched — its ballots stay credited to the
 * elected candidate permanently. That's what distinguishes an inclusive method
 * ({@link FractionalTransfer}, {@link WigmTransfer} — every parcel gets an entry) from a
 * last-parcel-only method ({@link LastParcelTransfer}, {@link LastParcelDeferredRoundingTransfer}
 * — only the most recently arrived parcel does).
 */
public sealed interface TransferMethod permits FractionalTransfer, WigmTransfer, LastParcelTransfer,
        LastParcelDeferredRoundingTransfer, UnweightedInclusiveTransfer, UnweightedExclusiveTransfer {

    Map<Integer, Fraction> computeTransferValues(Fraction surplus, List<HeldParcel> parcels, RoundingRule roundingRule);

    /**
     * Whether the ruleset's rounding applies to each candidate's gain from each transfer step
     * (a surplus, or one value group of an exclusion), tallies and exhausted votes being sums
     * of rounded gains, rather than to their running totals. The ACT, the Australian Senate and
     * Tasmania count this way.
     */
    default boolean roundsGainsPerStep() {
        return false;
    }
}
