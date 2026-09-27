package info.fkhodkov.rankedchoice.stv;

import java.util.List;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;

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
        // Tied rankings are only supported under fractional transfer so far -- WIGM and the
        // last-parcel family don't yet define how to split a tied ballot's value; see
        // StvCounter's class javadoc for the fractional-transfer algorithm that is supported,
        // verified against CRAN's `vote` package.
        if (!(ruleset.transferMethod() instanceof FractionalTransfer)) {
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
}
