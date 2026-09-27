package info.fkhodkov.rankedchoice.stv;

import java.util.List;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/** The outcome of a full STV count: who won, in what order, and the full round-by-round detail, plus
 * the ballots set aside as informal before it started. */
public record StvResult(
        List<Candidate> electedInOrder, List<RoundRecord> rounds, Fraction quota, InformalBallots informal) {
    public StvResult {
        electedInOrder = List.copyOf(electedInOrder);
        rounds = List.copyOf(rounds);
    }

    public StvResult(List<Candidate> electedInOrder, List<RoundRecord> rounds, Fraction quota) {
        this(electedInOrder, rounds, quota, InformalBallots.NONE);
    }
}
