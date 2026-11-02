package info.fkhodkov.rankedchoice.schulzestv;

import java.util.List;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.core.util.Unmodifiable;
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
    public SchulzeStvResult {
        supports = Unmodifiable.list(supports);
        potentialWinners = Unmodifiable.listOfSets(potentialWinners);
        winners = Unmodifiable.listOfSets(winners);
    }

    /**
     * {@code N[committee; against]}. The committee is copied by {@link Unmodifiable#set}, which
     * keeps a set it made already as it is: a count shares each committee's set among its supports
     * (807,576 supports of 42,504 committees for 24 candidates and 5 seats), and copying it into
     * each would take tens of megabytes.
     */
    public record Support(Set<Candidate> committee, Candidate against, Fraction support) {
        public Support {
            committee = Unmodifiable.set(committee);
        }
    }
}
