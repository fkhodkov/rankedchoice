package info.fkhodkov.rankedchoice.borda;

import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * A generic linearly-decreasing positional scoring rule: a first preference is worth {@code
 * startWeight} points, and each subsequent preference is worth {@code step} points less —
 * {@code weight(rank) = startWeight - step * rank}. {@link ClassicBorda} is the special case
 * {@code startWeight = totalCandidates - 1, step = 1}; other jurisdictions' "modified Borda"
 * variants (a fixed points schedule independent of the candidate count, e.g. {@code 4, 3, 2,
 * 1, 0, 0, ...} for exactly the top few preferences) don't fit this shape and would need their
 * own rule.
 */
public record ModifiedBorda(Fraction startWeight, Fraction step) implements BordaScoringRule {

    @Override
    public Fraction weight(int rank, int totalCandidates) {
        return startWeight.subtract(step.multiply(Fraction.of(rank)));
    }
}
