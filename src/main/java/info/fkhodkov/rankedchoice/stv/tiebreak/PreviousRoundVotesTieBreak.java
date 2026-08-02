package info.fkhodkov.rankedchoice.stv.tiebreak;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;

/**
 * Breaks a tie by looking back through earlier rounds (most recent first) for the first
 * round where the tied candidates' tallies actually differed: for elimination, whichever
 * had the lowest tally in that round is eliminated; for election order, whichever had the
 * highest is processed first. Falls back to another rule if the tied candidates were tied
 * in every prior round too (including when there's no history yet, in round 1 itself).
 */
public record PreviousRoundVotesTieBreak(TieBreakRule fallback) implements TieBreakRule {

    @Override
    public Candidate candidateToEliminate(List<Candidate> tied, List<RoundRecord> priorRounds) {
        return resolve(tied, priorRounds, true).orElseGet(() -> fallback.candidateToEliminate(tied, priorRounds));
    }

    @Override
    public Candidate candidateToElectFirst(List<Candidate> tied, List<RoundRecord> priorRounds) {
        return resolve(tied, priorRounds, false).orElseGet(() -> fallback.candidateToElectFirst(tied, priorRounds));
    }

    private static Optional<Candidate> resolve(List<Candidate> tied, List<RoundRecord> priorRounds,
            boolean pickLowest) {
        for (int i = priorRounds.size() - 1; i >= 0; i--) {
            Map<Candidate, Fraction> tallies = priorRounds.get(i).tallies();
            List<Candidate> present = tied.stream().filter(tallies::containsKey).toList();
            if (present.size() < 2) {
                continue;
            }
            Fraction extreme = pickLowest
                    ? present.stream().map(tallies::get).min(Fraction::compareTo).orElseThrow()
                    : present.stream().map(tallies::get).max(Fraction::compareTo).orElseThrow();
            List<Candidate> matching = present.stream()
                    .filter(candidate -> tallies.get(candidate).compareTo(extreme) == 0)
                    .toList();
            if (matching.size() == 1) {
                return Optional.of(matching.getFirst());
            }
        }
        return Optional.empty();
    }
}
