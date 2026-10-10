package info.fkhodkov.rankedchoice.stv;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * A snapshot of one round: every continuing candidate's tally at the start of the round
 * (before {@code action} was applied), the running total of votes that have become
 * non-transferable so far, and the votes lost to rounding so far.
 *
 * <p>{@code roundingLoss} is what official count sheets list as "non-transferable votes (due
 * to rounding)": the valid votes not accounted for by the tallies, the votes credited to
 * candidates already elected, and the exhausted votes, so that a round's figures add up to
 * the total. It's zero for counts that lose nothing to rounding, and for Meek and Warren,
 * whose residual ({@code exhaustedVotes}) already includes it. Under the ACT's Hare-Clark it
 * also holds the part of a surplus no transferable paper can carry, which the ACT's official
 * sheets count as "loss by fraction" too.
 */
public record RoundRecord(
        int roundNumber,
        Map<Candidate, Fraction> tallies,
        RoundAction action,
        Fraction exhaustedVotes,
        Fraction roundingLoss
) {
    public RoundRecord(int roundNumber, Map<Candidate, Fraction> tallies, RoundAction action, Fraction exhaustedVotes) {
        this(roundNumber, tallies, action, exhaustedVotes, Fraction.ZERO);
    }

    public RoundRecord {
        // Map.copyOf() doesn't preserve iteration order (its order is unspecified), which
        // would make tied candidates' display order effectively arbitrary; this keeps the
        // insertion order computeTallies() built (declared candidate order) while still
        // being immutable.
        tallies = Collections.unmodifiableMap(new LinkedHashMap<>(tallies));
    }
}
