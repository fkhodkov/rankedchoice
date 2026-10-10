package info.fkhodkov.rankedchoice.stv.tiebreak;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;

/**
 * Breaks a tie by each candidate's original first-preference count (round 1's tallies,
 * before any transfers) — the "count back to first preferences" fallback many rulesets use.
 * Falls back to another rule if the tie is in round 1 itself (no history to count back to)
 * or the first-preference counts were tied too.
 */
public record FirstPreferenceCountTieBreak(TieBreakRule fallback) implements TieBreakRule {

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
        if (priorRounds.isEmpty()) {
            return Optional.empty();
        }
        Map<Candidate, Fraction> firstPreferences = priorRounds.getFirst().tallies();
        List<Candidate> present = tied.stream().filter(firstPreferences::containsKey).toList();
        if (present.size() < 2) {
            return Optional.empty();
        }
        Fraction extreme = pickLowest
                ? present.stream().map(firstPreferences::get).min(Fraction::compareTo).orElseThrow()
                : present.stream().map(firstPreferences::get).max(Fraction::compareTo).orElseThrow();
        List<Candidate> matching = present.stream()
                .filter(candidate -> firstPreferences.get(candidate).compareTo(extreme) == 0)
                .toList();
        return matching.size() == 1 ? Optional.of(matching.getFirst()) : Optional.empty();
    }
}
