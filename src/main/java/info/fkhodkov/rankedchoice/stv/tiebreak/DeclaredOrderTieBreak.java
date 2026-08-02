package info.fkhodkov.rankedchoice.stv.tiebreak;

import java.util.List;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/**
 * Breaks ties by a fixed candidate order (e.g. the order candidates are declared in the
 * ballot file) — of the tied candidates, the one appearing earliest in that order is
 * returned. A simple, fully deterministic fallback; richer rules (comparing earlier rounds'
 * tallies, a seeded random draw) are a later addition.
 */
public record DeclaredOrderTieBreak(List<Candidate> order) implements TieBreakRule {

    public DeclaredOrderTieBreak {
        order = List.copyOf(order);
    }

    @Override
    public Candidate resolveTie(List<Candidate> tied) {
        if (tied.isEmpty()) {
            throw new IllegalArgumentException("tied must not be empty");
        }
        for (Candidate candidate : order) {
            if (tied.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "none of the tied candidates appear in the declared order: " + tied);
    }
}
