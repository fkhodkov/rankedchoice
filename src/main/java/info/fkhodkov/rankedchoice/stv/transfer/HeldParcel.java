package info.fkhodkov.rankedchoice.stv.transfer;

import java.math.BigInteger;
import java.util.List;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * One group of ballots an elected candidate currently holds, all at the same value and all
 * arrived via the same transfer event ({@code arrivalParcelId}). {@code id} is just a stable
 * key for {@link TransferMethod#computeTransferValues} to report its decision against — it
 * carries no meaning beyond identity.
 */
public record HeldParcel(int id, int arrivalParcelId, Fraction value, BigInteger voteCount) {

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
