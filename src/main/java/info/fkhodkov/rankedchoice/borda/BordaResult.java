package info.fkhodkov.rankedchoice.borda;

import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/** Every candidate's total score, and the resulting ranking from highest score to lowest. */
public record BordaResult(Map<Candidate, Fraction> scores, List<Candidate> rankingBestToWorst) {
}
