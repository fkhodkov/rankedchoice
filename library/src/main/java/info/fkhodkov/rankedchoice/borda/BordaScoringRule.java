package info.fkhodkov.rankedchoice.borda;

import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * How many points a single vote at a given preference rank is worth. {@code rank} is
 * 0-indexed (0 = first preference); {@code totalCandidates} is how many candidates stand in
 * the election as a whole, needed by {@link ClassicBorda} but not the other variants.
 */
public sealed interface BordaScoringRule permits ClassicBorda, DowdallScoring, ModifiedBorda {

    Fraction weight(int rank, int totalCandidates);
}
