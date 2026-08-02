package info.fkhodkov.rankedchoice.core.ballot;

import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

import java.util.List;
import java.util.Set;

/**
 * The result of parsing a full election file (e.g. .blt): the candidate roster, the seat
 * count, any withdrawn candidates, and the ballots themselves.
 */
public record ParsedElection(
        String title,
        List<Candidate> candidates,
        Set<Candidate> withdrawnCandidates,
        int seats,
        List<Ballot> ballots
) {
    public ParsedElection {
        candidates = List.copyOf(candidates);
        withdrawnCandidates = Set.copyOf(withdrawnCandidates);
        ballots = List.copyOf(ballots);
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive, got: " + seats);
        }
    }
}
