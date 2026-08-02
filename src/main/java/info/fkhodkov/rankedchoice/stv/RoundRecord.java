package info.fkhodkov.rankedchoice.stv;

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
        tallies = Map.copyOf(tallies);
    }
}
