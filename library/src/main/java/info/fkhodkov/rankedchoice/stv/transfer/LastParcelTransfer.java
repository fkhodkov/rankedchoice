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
 * <p>The transfer value is {@code surplus / (number of that parcel's ballot papers with a
 * further preference)}, and each of those papers moves on at that value, whatever value it
 * arrived at: it's a value per paper, not a ratio of the paper's own value. That's how pyRCV2
 * counts the ERS97 model election (exclusive Gregory, unweighted), and what moves the whole
 * surplus when a candidate is elected on papers that themselves came from a surplus. The
 * denominator counts papers, confirmed against both the ACT Electoral Act's Schedule 4 ("CP
 * means the number of ballot papers ... that specify a next available preference") and
 * opavote's citation of ERS97 rule 5.3.7 ("the number of transferable papers"). Papers with
 * <em>no</em> further preference are excluded from that denominator entirely (unlike
 * {@link FractionalTransfer}/{@link WigmTransfer}, whose denominator is every vote the
 * candidate holds) and aren't transferred at all: they stay with the elected candidate as part
 * of the quota. Only what the rounded transfer value fails to pass on is lost, which is what the ERS97
 * rulebook's model election shows as non-transferable (0.59 of Smith's 26.42 surplus, not the
 * value of his 11 papers with no further preference).
 *
 * <p>When the surplus is at least the transferable papers' total value, they move at the
 * value they arrived at; a paper never gains value. The rest of the surplus can't be passed on
 * and is non-transferable (the engine counts it as exhausted). That's ERS97's rule as pyRCV2
 * implements it ("do not allow weight to increase"), and the ACT's: a transfer value never
 * exceeds the papers' own.
 *
 * <p>This rounds the transfer value itself, once, before applying it to every transferable
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

        // Papers with no further preference get no entry: they stay with the elected candidate.
        Map<Integer, Fraction> result = new LinkedHashMap<>();
        List<HeldParcel> transferable = lastParcel.stream().filter(HeldParcel::hasNextPreference).toList();
        BigInteger transferablePapers = transferable.stream()
                .map(HeldParcel::voteCount)
                .reduce(BigInteger.ZERO, BigInteger::add);
        if (transferablePapers.signum() > 0 && surplus.compareTo(totalValue(transferable)) >= 0) {
            for (HeldParcel parcel : transferable) {
                result.put(parcel.id(), parcel.value());
            }
        } else if (transferablePapers.signum() > 0) {
            Fraction transferValue = roundingRule.apply(surplus.divide(Fraction.of(transferablePapers)));
            for (HeldParcel parcel : transferable) {
                result.put(parcel.id(), transferValue);
            }
        }
        return result;
    }

    static Fraction totalValue(List<HeldParcel> parcels) {
        Fraction total = Fraction.ZERO;
        for (HeldParcel parcel : parcels) {
            total = total.add(parcel.totalValue());
        }
        return total;
    }
}
