package info.fkhodkov.rankedchoice.qpq;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * A snapshot of one QPQ stage: every hopeful candidate's quotient, the quota it was measured
 * against (recomputed every stage — see {@link QpqCounter}), and what happened as a result.
 *
 * <p>{@code restarted} is true when this stage's quotients were computed after a full restart
 * — every previously-elected candidate returned to hopeful, every ballot's accumulated
 * elected-credit reset to zero, and every ballot re-walked from its first preference against
 * the surviving (non-excluded) candidates. Woodall's rule triggers this after every exclusion
 * (see {@link QpqCounter}'s class javadoc for why); the very first stage counts as one too,
 * since it starts from the same blank slate.
 */
public record QpqRoundRecord(
        int stageNumber,
        Map<Candidate, Fraction> quotients,
        Fraction quota,
        QpqAction action,
        boolean restarted
) {
    public QpqRoundRecord {
        quotients = Collections.unmodifiableMap(new LinkedHashMap<>(quotients));
    }
}
