package info.fkhodkov.rankedchoice.stv;

import java.util.List;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/** The outcome of a full STV count: who won, in what order, and the full round-by-round detail. */
public record StvResult(List<Candidate> electedInOrder, List<RoundRecord> rounds, Fraction quota) {
    public StvResult {
        electedInOrder = List.copyOf(electedInOrder);
        rounds = List.copyOf(rounds);
    }
}
