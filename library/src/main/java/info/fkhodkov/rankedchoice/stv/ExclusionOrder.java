package info.fkhodkov.rankedchoice.stv;

/**
 * The order an excluded candidate's papers move in, each group as its own transfer, under the
 * transfer methods that exclude in steps (last-parcel, unweighted and weighted-inclusive Gregory):
 * a candidate who reaches quota partway through receives nothing from the later groups.
 */
public enum ExclusionOrder {
    /** By the value the papers arrived at, highest first: ERS97, the ACT, Tasmania, the Senate. */
    VALUE,
    /**
     * First preferences first, then the papers received on transfers by value, highest first;
     * papers that arrived at a value of 1 move with the second group, not the first. Victoria's
     * Legislative Council (Electoral Act 2002 s114A(12), (28)).
     */
    FIRST_PREFERENCES_THEN_VALUE,
    /**
     * First preferences, then each parcel in the order it arrived. Western Australia's
     * Legislative Council (Electoral Act 1907 Schedule 1, clauses 8 and 19).
     */
    PARCEL
}
