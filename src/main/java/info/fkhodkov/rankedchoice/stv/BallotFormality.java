package info.fkhodkov.rankedchoice.stv;

import java.math.BigInteger;
import java.util.ArrayList;
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

    /** The ballots a count runs on (none of them blank), and the ones set aside, by reason. */
    public record FormalBallots(List<Ballot> ballots, InformalBallots informal) {
        public FormalBallots {
            ballots = List.copyOf(ballots);
        }
    }

    private BallotFormality() {
    }

    public static FormalBallots apply(
            List<Ballot> ballots, TiedRankingPolicy tiedRankings, int minimumPreferences, int candidateCount) {
        List<Ballot> afterTies = tiedRankings.apply(ballots);
        int minimum = Math.min(minimumPreferences, candidateCount - 1);

        // Every policy maps ballots one-to-one and in order, so position i is the same paper.
        BigInteger blank = BigInteger.ZERO;
        BigInteger voidedByTies = BigInteger.ZERO;
        BigInteger tooFew = BigInteger.ZERO;
        List<Ballot> formal = new ArrayList<>(afterTies.size());
        for (int i = 0; i < afterTies.size(); i++) {
            Ballot ballot = afterTies.get(i);
            if (ballots.get(i).isBlank()) {
                blank = blank.add(ballot.count());
            } else if (ballot.isBlank()) {
                voidedByTies = voidedByTies.add(ballot.count());
            } else if (ballot.ranking().size() < minimum) {
                tooFew = tooFew.add(ballot.count());
            } else {
                formal.add(ballot);
            }
        }
        if (formal.isEmpty()) {
            // Otherwise every candidate sits on zero and seats would be "won" purely on tie-break order.
            throw new IllegalArgumentException("No valid votes to count: every ballot is blank or informal "
                    + "under this ruleset's tied-ranking and minimum-preferences rules");
        }
        return new FormalBallots(formal, new InformalBallots(blank, voidedByTies, tooFew));
    }
}
