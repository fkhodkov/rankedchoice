package info.fkhodkov.rankedchoice.schulzestv;

import java.util.List;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.schulze.SchulzeResult;

/**
 * The outcome of a Schulze STV count (see {@link SchulzeStvCounter}).
 *
 * <p>{@code supports} holds every {@code N[committee; against]}: how strongly {@code
 * committee} holds against the one candidate {@code against} that could replace a member.
 * There is one per committee and outsider, listed contest by contest. Every link's strength is
 * built from two of them.
 *
 * <p>{@code potentialWinners} are the committees no other committee beats by strongest path
 * (Stage 3). {@code winners} are those left after Stage 4's tie-break by {@code
 * candidateRanking}, the single-winner Schulze ranking; there's more than one only in a
 * genuine tie.
 */
public record SchulzeStvResult(
        List<Support> supports,
        List<Set<Candidate>> potentialWinners,
        List<Set<Candidate>> winners,
        SchulzeResult candidateRanking
) {
    /** {@code N[committee; against]}. */
    public record Support(Set<Candidate> committee, Candidate against, Fraction support) {
    }
}
