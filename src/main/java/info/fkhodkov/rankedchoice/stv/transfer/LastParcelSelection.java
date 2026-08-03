package info.fkhodkov.rankedchoice.stv.transfer;

import java.util.List;

/**
 * Shared parcel-selection logic for last-parcel-family transfer methods ({@link
 * LastParcelTransfer}, {@link LastParcelDeferredRoundingTransfer}): both examine only the
 * single most-recently-arrived parcel a candidate holds.
 */
final class LastParcelSelection {

    private LastParcelSelection() {
    }

    static List<HeldParcel> mostRecentArrival(List<HeldParcel> parcels) {
        if (parcels.isEmpty()) {
            return List.of();
        }
        int lastArrivalId = parcels.stream().mapToInt(HeldParcel::arrivalParcelId).max().orElseThrow();
        return parcels.stream().filter(parcel -> parcel.arrivalParcelId() == lastArrivalId).toList();
    }
}
