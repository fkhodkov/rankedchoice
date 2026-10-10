package info.fkhodkov.rankedchoice.borda;

import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * The Dowdall system (also called the Nauru system, after its real-world use in Nauru's
 * parliamentary elections): a first preference is worth 1 point, a second preference {@code
 * 1/2}, a third {@code 1/3}, and so on — independent of how many candidates stand in total,
 * unlike {@link ClassicBorda}.
 */
public record DowdallScoring() implements BordaScoringRule {

    public static final DowdallScoring INSTANCE = new DowdallScoring();

    @Override
    public Fraction weight(int rank, int totalCandidates) {
        return Fraction.of(1, rank + 1);
    }
}
