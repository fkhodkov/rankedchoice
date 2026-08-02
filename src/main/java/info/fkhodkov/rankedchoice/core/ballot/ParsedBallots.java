package info.fkhodkov.rankedchoice.core.ballot;

import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

import java.util.List;

/**
 * The result of parsing a ballot file that carries no explicit candidate roster or seat
 * count of its own (e.g. CSV) — the candidate list is simply every candidate name
 * encountered, in first-seen order.
 */
public record ParsedBallots(List<Candidate> candidates, List<Ballot> ballots) {
    public ParsedBallots {
        candidates = List.copyOf(candidates);
        ballots = List.copyOf(ballots);
    }
}
