package info.fkhodkov.rankedchoice.stv;

import java.util.List;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.LastParcelDeferredRoundingTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.LastParcelTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.TransferMethod;
import info.fkhodkov.rankedchoice.stv.transfer.WigmTransfer;

/**
 * Preconditions for {@link StvCounter#count}. Kept separate from the counting algorithm
 * itself so the round loop isn't interleaved with input checks.
 */
final class StvCounterValidation {

    private StvCounterValidation() {
    }

    static void validate(List<Candidate> candidates, List<Ballot> ballots, int seats, StvRuleset ruleset) {
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive, got: " + seats);
        }
        if (seats > candidates.size()) {
            throw new IllegalArgumentException(
                    "cannot fill " + seats + " seat(s) with only " + candidates.size() + " candidate(s)");
        }
        if (!supportsTiedRankings(ruleset.transferMethod())) {
            for (Ballot ballot : ballots) {
                for (Set<Candidate> rank : ballot.ranking()) {
                    if (rank.size() > 1) {
                        throw new UnsupportedOperationException(
                                "Tied rankings are only supported under fractional transfer for now; "
                                        + "WIGM and last-parcel-family transfer methods don't yet define how to "
                                        + "split a tied ballot's value");
                    }
                }
            }
        }
    }

    /**
     * Whether a transfer method has a defined rule for splitting a tied ballot's value. Only
     * fractional transfer does so far (see {@link StvCounter}'s class javadoc for the
     * algorithm, verified against CRAN's {@code vote} package). Deliberately an exhaustive
     * switch over the sealed {@link TransferMethod} rather than an {@code instanceof} check,
     * so adding a new transfer method fails to compile here until someone decides whether it
     * supports ties, instead of silently inheriting a rejection (or an acceptance) by default.
     */
    private static boolean supportsTiedRankings(TransferMethod transferMethod) {
        return switch (transferMethod) {
            case FractionalTransfer _ -> true;
            case WigmTransfer _, LastParcelTransfer _, LastParcelDeferredRoundingTransfer _ -> false;
        };
    }
}
