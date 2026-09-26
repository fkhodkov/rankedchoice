package info.fkhodkov.rankedchoice.qpq;

import java.util.List;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/** The outcome of a full QPQ count: who won, in what order, and the full stage-by-stage detail. */
public record QpqResult(List<Candidate> electedInOrder, List<QpqRoundRecord> rounds) {
    public QpqResult {
        electedInOrder = List.copyOf(electedInOrder);
        rounds = List.copyOf(rounds);
    }
}
