package info.fkhodkov.rankedchoice.qpq;

import java.util.List;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.InformalBallots;

/** The outcome of a full QPQ count: who won, in what order, and the full stage-by-stage detail, plus
 * the ballots set aside as informal before it started. */
public record QpqResult(List<Candidate> electedInOrder, List<QpqRoundRecord> rounds, InformalBallots informal) {
    public QpqResult {
        electedInOrder = List.copyOf(electedInOrder);
        rounds = List.copyOf(rounds);
    }

    public QpqResult(List<Candidate> electedInOrder, List<QpqRoundRecord> rounds) {
        this(electedInOrder, rounds, InformalBallots.NONE);
    }
}
