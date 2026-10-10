package info.fkhodkov.rankedchoice.stv.transfer;

import java.math.BigInteger;
import java.util.List;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * One group of ballots an elected candidate currently holds, all at the same value, all
 * arrived via the same transfer event ({@code arrivalParcelId}), and all agreeing on whether
 * they have a next continuing preference ({@code hasNextPreference}) — a ballot with none
 * would become non-transferable the moment it's touched. {@code id} is just a stable key for
 * {@link TransferMethod#computeTransferValues} to report its decision against — it carries no
 * meaning beyond identity.
 *
 * <p>{@code hasNextPreference} only matters to {@link LastParcelTransfer}: last-parcel-family
 * rulesets (ERS97/76, Hare-Clark) define the transfer ratio's denominator as the number of
 * ballot <em>papers</em> with a further preference, excluding ones that would become
 * non-transferable — unlike {@link FractionalTransfer}/{@link WigmTransfer}, whose denominator
 * is every vote the candidate holds regardless. {@link FractionalTransfer}/{@link WigmTransfer}
 * ignore this field entirely.
 */
public record HeldParcel(int id, int arrivalParcelId, Fraction value, BigInteger voteCount, boolean hasNextPreference) {

    /** This parcel's total vote weight: {@code value * voteCount}. */
    public Fraction totalValue() {
        return value.multiply(Fraction.of(voteCount));
    }

    /** The combined vote weight of every given parcel. */
    public static Fraction totalOf(List<HeldParcel> parcels) {
        Fraction total = Fraction.ZERO;
        for (HeldParcel parcel : parcels) {
            total = total.add(parcel.totalValue());
        }
        return total;
    }
}
