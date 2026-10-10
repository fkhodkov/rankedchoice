package info.fkhodkov.rankedchoice.stv;

import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/** What happened to a single candidate in one round of the count. */
public sealed interface RoundAction {

    Candidate candidate();

    /**
     * A candidate reaching or exceeding quota. {@code surplusTransferred} is
     * {@link Fraction#ZERO} either because the candidate landed exactly on quota, or because
     * they were elected via the "remaining continuing candidates == remaining seats" rule
     * without a transfer being needed.
     */
    record Elected(Candidate candidate, Fraction surplusTransferred) implements RoundAction {
    }

    /** The lowest-placed continuing candidate, removed and their votes transferred onward. */
    record Eliminated(Candidate candidate) implements RoundAction {
    }
}
