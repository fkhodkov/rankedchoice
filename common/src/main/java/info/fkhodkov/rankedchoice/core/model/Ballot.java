package info.fkhodkov.rankedchoice.core.model;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A single ballot's ranking, plus how many voters cast an identical ballot ({@code count}).
 * Each rank is a {@code Set<Candidate>} rather than a single candidate so that equal
 * preferences (e.g. a "3=4=5" tie group in a .blt file) can be represented without changing
 * the model shape later — counting engines that don't yet support ties can simply require
 * every rank to be a singleton.
 */
public record Ballot(List<Set<Candidate>> ranking, BigInteger count) {

    public Ballot {
        if (ranking == null) {
            throw new IllegalArgumentException("ranking must not be null");
        }
        if (count == null || count.signum() <= 0) {
            throw new IllegalArgumentException("count must be positive, got: " + count);
        }

        // A SingletonRanking is immutable and checked as it's made, and kept as it is.
        if (!(ranking instanceof SingletonRanking)) {
            List<Set<Candidate>> copy = new ArrayList<>(ranking.size());
            Set<Candidate> seen = new HashSet<>();
            for (Set<Candidate> rank : ranking) {
                if (rank == null || rank.isEmpty()) {
                    throw new IllegalArgumentException("a ranking cannot contain an empty rank");
                }
                for (Candidate candidate : rank) {
                    if (!seen.add(candidate)) {
                        throw new IllegalArgumentException(
                                "candidate " + candidate.id() + " appears more than once in the same ballot");
                    }
                }
                copy.add(Set.copyOf(rank));
            }
            ranking = List.copyOf(copy);
        }
    }

    /** True if this ballot ranks no candidates at all (a blank ballot). */
    public boolean isBlank() {
        return ranking.isEmpty();
    }

    public Set<Candidate> firstPreference() {
        return ranking.getFirst();
    }

    public Set<Candidate> lastPreference() {
        return ranking.getLast();
    }
}
