package info.fkhodkov.rankedchoice.qpq;

import info.fkhodkov.rankedchoice.core.model.Candidate;

/** What happened to a single candidate in one stage of a QPQ count. */
public sealed interface QpqAction {

    Candidate candidate();

    /** A candidate whose quotient exceeded the quota this stage. */
    record Elected(Candidate candidate) implements QpqAction {
    }

    /** The lowest-quotient hopeful candidate this stage, permanently excluded. */
    record Excluded(Candidate candidate) implements QpqAction {
    }
}
