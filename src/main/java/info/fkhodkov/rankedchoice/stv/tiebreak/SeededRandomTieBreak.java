package info.fkhodkov.rankedchoice.stv.tiebreak;

import java.util.List;
import java.util.Random;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.RoundRecord;

/**
 * Breaks a tie with a seeded random draw: reproducible given the same seed and the same
 * sequence of ties encountered, but otherwise arbitrary. Used when a ruleset specifically
 * calls for a random draw rather than a rule-based fallback. Not a record: two draws from
 * the same seed are only reproducible if each gets its own instance, since drawing advances
 * the underlying generator's state.
 */
public final class SeededRandomTieBreak implements TieBreakRule {

    private final Random random;

    public SeededRandomTieBreak(long seed) {
        this.random = new Random(seed);
    }

    @Override
    public Candidate candidateToEliminate(List<Candidate> tied, List<RoundRecord> priorRounds) {
        return pick(tied);
    }

    @Override
    public Candidate candidateToElectFirst(List<Candidate> tied, List<RoundRecord> priorRounds) {
        return pick(tied);
    }

    private Candidate pick(List<Candidate> tied) {
        if (tied.isEmpty()) {
            throw new IllegalArgumentException("tied must not be empty");
        }
        return tied.get(random.nextInt(tied.size()));
    }
}
