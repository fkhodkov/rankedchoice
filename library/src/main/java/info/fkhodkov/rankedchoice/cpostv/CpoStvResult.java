package info.fkhodkov.rankedchoice.cpostv;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.core.util.Unmodifiable;
import info.fkhodkov.rankedchoice.stv.InformalBallots;

/**
 * The outcome of a CPO-STV count: every possible {@code seats}-sized outcome's score against
 * every other, and the resulting ranking from most to least preferred. As with {@link
 * info.fkhodkov.rankedchoice.schulze.SchulzeResult}, {@code ranking} groups outcomes that tied
 * — scored exactly level against each other in both directions — together in one {@link Set};
 * a genuine, unresolved tie for the win is a {@code ranking.getFirst()} of size greater than 1.
 * {@code informal} counts the ballots set aside before the count started.
 */
public record CpoStvResult(
        Map<Set<Candidate>, Map<Set<Candidate>, Fraction>> outcomeScores,
        List<Set<Set<Candidate>>> ranking,
        InformalBallots informal
) {
    /**
     * Copies the scores and the ranking, outcomes too. An outcome is a set of candidates in no
     * order, so {@link Set#copyOf} does: it keeps the count's own outcomes, already immutable, as
     * they are.
     */
    public CpoStvResult {
        Map<Set<Candidate>, Map<Set<Candidate>, Fraction>> scores = new LinkedHashMap<>();
        outcomeScores.forEach((outcome, row) -> {
            Map<Set<Candidate>, Fraction> copied = new LinkedHashMap<>();
            row.forEach((against, score) -> copied.put(Set.copyOf(against), score));
            scores.put(Set.copyOf(outcome), copied);
        });
        outcomeScores = Unmodifiable.mapOfMaps(scores);
        ranking = Unmodifiable.listOfSets(ranking.stream()
                .map(tied -> tied.stream().map(Set::copyOf).collect(Collectors.toCollection(LinkedHashSet::new)))
                .toList());
    }

    public CpoStvResult(
            Map<Set<Candidate>, Map<Set<Candidate>, Fraction>> outcomeScores, List<Set<Set<Candidate>>> ranking) {
        this(outcomeScores, ranking, InformalBallots.NONE);
    }

    /** The winning outcome(s) — more than one only when the count ends in a genuine tie. */
    public Set<Set<Candidate>> winners() {
        return ranking.getFirst();
    }
}
