package info.fkhodkov.rankedchoice.stv;

import java.util.List;
import info.fkhodkov.rankedchoice.core.model.Ballot;

/**
 * The ballot preprocessing every ranked counting engine shares before its count starts:
 * applying a {@link TiedRankingPolicy}, then a minimum-preferences formality rule (a ballot
 * ranking fewer candidates than {@code minimumPreferences} is informal, capped at one fewer
 * than the number of candidates — see {@link StvRuleset} for the Tasmanian statute this
 * reproduces), then refusing to count at all if no ballot survives.
 *
 * <p>{@link TiedRankingPolicy#SPLIT} passes tied ballots through untouched, so a caller must
 * only allow it when its own engine actually knows how to split them.
 */
public final class BallotFormality {

    private BallotFormality() {
    }

    public static List<Ballot> apply(
            List<Ballot> ballots, TiedRankingPolicy tiedRankings, int minimumPreferences, int candidateCount) {
        List<Ballot> formal = tiedRankings.apply(ballots);
        int minimum = Math.min(minimumPreferences, candidateCount - 1);
        if (minimum > 0) {
            formal = formal.stream().filter(ballot -> ballot.ranking().size() >= minimum).toList();
        }
        if (formal.stream().allMatch(Ballot::isBlank)) {
            // Otherwise every candidate sits on zero and seats would be "won" purely on tie-break order.
            throw new IllegalArgumentException("No valid votes to count: every ballot is blank or informal "
                    + "under this ruleset's tied-ranking and minimum-preferences rules");
        }
        return formal;
    }
}
