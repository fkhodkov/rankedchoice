package info.fkhodkov.rankedchoice.stv;

import java.util.List;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/**
 * Preconditions for {@link StvCounter#count}. Kept separate from the counting algorithm
 * itself so the round loop isn't interleaved with input checks. Tied rankings are checked by
 * the ruleset's {@link TiedRankingPolicy} instead, since whether they're acceptable depends on
 * the policy chosen.
 */
final class StvCounterValidation {

    private StvCounterValidation() {
    }

    static void validate(List<Candidate> candidates, int seats) {
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive, got: " + seats);
        }
        if (seats > candidates.size()) {
            throw new IllegalArgumentException(
                    "cannot fill " + seats + " seat(s) with only " + candidates.size() + " candidate(s)");
        }
    }
}
