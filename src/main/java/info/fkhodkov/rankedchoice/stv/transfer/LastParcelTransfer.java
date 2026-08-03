package info.fkhodkov.rankedchoice.stv.transfer;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * The "last parcel only" (exclusive Gregory) method used by e.g. ERS97/76 and Hare-Clark:
 * unlike {@link FractionalTransfer}/{@link WigmTransfer}, only the single most recently
 * arrived parcel is examined for a surplus — ballots the candidate received earlier stay
 * credited to them, unchanged, for good.
 *
 * <p>The transfer ratio is {@code surplus / (number of that parcel's ballot papers with a
 * further preference)} — confirmed against both the ACT Electoral Act's Schedule 4 ("CP
 * means the number of ballot papers ... that specify a next available preference") and
 * opavote's citation of ERS97 rule 5.3.7 ("the number of transferable papers"). Papers with
 * <em>no</em> further preference are excluded from that denominator entirely (unlike
 * {@link FractionalTransfer}/{@link WigmTransfer}, whose denominator is every vote the
 * candidate holds) and simply carry their existing value forward into non-transferability,
 * unscaled — scaling them too would redistribute more than the true surplus.
 *
 * <p>A parcel arriving as one transfer event is assumed to be internally uniform in value —
 * true by construction for a ruleset that only ever uses last-parcel transfers itself, but
 * not guaranteed if it's mixed with an inclusive method elsewhere in the same count. If the
 * identified last parcel does turn out to hold more than one value (multiple {@link
 * HeldParcel} entries sharing the same {@code arrivalParcelId}), each is scaled by its own
 * value against the shared transferable-paper count, which reduces to the same result in the
 * uniform case and degrades gracefully rather than silently miscounting otherwise.
 *
 * <p>This rounds the shared ratio itself, once, before applying it to every transferable
 * paper — the convention confirmed for ERS97/76 and Tasmania's Hare-Clark count. The ACT
 * Legislative Assembly's own Hare-Clark variant instead keeps the ratio exact and rounds each
 * destination candidate's resulting tally — see {@link LastParcelDeferredRoundingTransfer}.
 */
public record LastParcelTransfer() implements TransferMethod {

    public static final LastParcelTransfer INSTANCE = new LastParcelTransfer();

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
                // No further preference: this paper becomes non-transferable outright,
                // carrying whatever value it already had -- it never entered the ratio.
                result.put(parcel.id(), parcel.value());
            }
        }

        List<HeldParcel> transferable = lastParcel.stream().filter(HeldParcel::hasNextPreference).toList();
        BigInteger transferablePapers = transferable.stream()
                .map(HeldParcel::voteCount)
                .reduce(BigInteger.ZERO, BigInteger::add);
        if (transferablePapers.signum() > 0) {
            Fraction ratio = roundingRule.apply(surplus.divide(Fraction.of(transferablePapers)));
            for (HeldParcel parcel : transferable) {
                result.put(parcel.id(), parcel.value().multiply(ratio));
            }
        }
        return result;
    }
}
