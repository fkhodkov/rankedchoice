package info.fkhodkov.rankedchoice.borda;

import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * The original Borda count: a first preference among {@code n} candidates is worth {@code n-1}
 * points, a second preference {@code n-2}, and so on down to {@code 0} for a last preference.
 * A candidate a ballot doesn't rank at all gets no points from it — not the {@code 0} a literal
 * last preference would also score, but the same practical effect.
 */
public record ClassicBorda() implements BordaScoringRule {

    public static final ClassicBorda INSTANCE = new ClassicBorda();

    @Override
    public Fraction weight(int rank, int totalCandidates) {
        return Fraction.of(totalCandidates - 1 - rank);
    }
}
