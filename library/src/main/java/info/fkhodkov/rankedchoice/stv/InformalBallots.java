package info.fkhodkov.rankedchoice.stv;

import java.math.BigInteger;

/**
 * How many ballots {@link BallotFormality} set aside before a count started, by reason. None of
 * them counts toward the valid vote a quota is based on.
 *
 * @param blank ballots that ranked nobody to begin with
 * @param tiedRankings ballots the tied-ranking policy left empty — under {@code truncate} a tie
 *     at first preference, under {@code skip} a ballot with nothing but tied ranks
 * @param tooFewPreferences ballots that still ranked someone, but fewer candidates than the
 *     minimum-preferences rule requires
 */
public record InformalBallots(BigInteger blank, BigInteger tiedRankings, BigInteger tooFewPreferences) {

    public static final InformalBallots NONE = new InformalBallots(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);

    public InformalBallots {
        if (blank.signum() < 0 || tiedRankings.signum() < 0 || tooFewPreferences.signum() < 0) {
            throw new IllegalArgumentException("informal ballot counts must not be negative");
        }
    }

    public BigInteger total() {
        return blank.add(tiedRankings).add(tooFewPreferences);
    }

    /**
     * A one-line summary for reports, naming only the reasons that apply, e.g. {@code "5 (too
     * few preferences: 3, tied rankings: 2)"}.
     */
    public String describe() {
        StringBuilder reasons = new StringBuilder();
        appendReason(reasons, "blank", blank);
        appendReason(reasons, "tied rankings", tiedRankings);
        appendReason(reasons, "too few preferences", tooFewPreferences);
        return total() + " (" + reasons + ")";
    }

    private static void appendReason(StringBuilder reasons, String label, BigInteger count) {
        if (count.signum() == 0) {
            return;
        }
        if (!reasons.isEmpty()) {
            reasons.append(", ");
        }
        reasons.append(label).append(": ").append(count);
    }
}
