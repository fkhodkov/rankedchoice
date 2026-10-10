package info.fkhodkov.rankedchoice.stv.tiebreak;

import java.util.List;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.RoundRecord;

/**
 * Breaks ties by a fixed candidate order (e.g. the order candidates are declared in the
 * ballot file) — of the tied candidates, the one appearing earliest in that order is
 * returned, for both elimination and election order. A simple, fully deterministic
 * fallback that ignores round history entirely.
 */
public record DeclaredOrderTieBreak(List<Candidate> order) implements TieBreakRule {

    public DeclaredOrderTieBreak {
        order = List.copyOf(order);
    }

    @Override
    public Candidate candidateToEliminate(List<Candidate> tied, List<RoundRecord> priorRounds) {
        return earliestInOrder(tied);
    }

    @Override
    public Candidate candidateToElectFirst(List<Candidate> tied, List<RoundRecord> priorRounds) {
        return earliestInOrder(tied);
    }

    private Candidate earliestInOrder(List<Candidate> tied) {
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
