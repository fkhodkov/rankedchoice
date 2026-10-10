package info.fkhodkov.rankedchoice.stv.tiebreak;

import java.util.List;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.RoundRecord;

/**
 * Breaks a tie between candidates with equal tallies, deterministically, given the rounds
 * counted so far (oldest first; not including the round currently being decided). Election
 * and elimination ties are kept as separate methods because they aren't always resolved the
 * same way — e.g. {@link PreviousRoundVotesTieBreak} eliminates whoever trailed earlier but
 * would elect first whoever led earlier, the opposite direction.
 */
public sealed interface TieBreakRule
        permits DeclaredOrderTieBreak, PreviousRoundVotesTieBreak, FirstPreferenceCountTieBreak, SeededRandomTieBreak {

    /** Of several candidates tied for lowest, which one to eliminate. */
    Candidate candidateToEliminate(List<Candidate> tied, List<RoundRecord> priorRounds);

    /**
     * Of several candidates tied for highest (all meeting quota in the same round), which
     * one to elect and transfer first.
     */
    Candidate candidateToElectFirst(List<Candidate> tied, List<RoundRecord> priorRounds);
}
