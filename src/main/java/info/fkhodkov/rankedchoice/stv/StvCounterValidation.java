package info.fkhodkov.rankedchoice.stv;

import java.util.List;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/**
 * Preconditions for {@link StvCounter#count}. Kept separate from the counting algorithm
 * itself so the round loop isn't interleaved with input checks.
 */
final class StvCounterValidation {

    private StvCounterValidation() {
    }

    static void validate(List<Candidate> candidates, List<Ballot> ballots, int seats) {
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive, got: " + seats);
        }
        if (seats > candidates.size()) {
            throw new IllegalArgumentException(
                    "cannot fill " + seats + " seat(s) with only " + candidates.size() + " candidate(s)");
        }
        for (Ballot ballot : ballots) {
            for (Set<Candidate> rank : ballot.ranking()) {
                if (rank.size() > 1) {
                    throw new UnsupportedOperationException("StvCounter does not yet support tied rankings");
                }
            }
        }
    }
}
