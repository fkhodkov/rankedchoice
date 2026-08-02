package info.fkhodkov.rankedchoice.stv;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * A snapshot of one round: every continuing candidate's tally at the start of the round
 * (before {@code action} was applied), and the running total of votes that have become
 * non-transferable so far.
 */
public record RoundRecord(
        int roundNumber,
        Map<Candidate, Fraction> tallies,
        RoundAction action,
        Fraction exhaustedVotes
) {
    public RoundRecord {
        // Map.copyOf() doesn't preserve iteration order (its order is unspecified), which
        // would make tied candidates' display order effectively arbitrary; this keeps the
        // insertion order computeTallies() built (declared candidate order) while still
        // being immutable.
        tallies = Collections.unmodifiableMap(new LinkedHashMap<>(tallies));
    }
}
