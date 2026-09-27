package info.fkhodkov.rankedchoice.stv;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.LastParcelDeferredRoundingTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.LastParcelTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.TransferMethod;
import info.fkhodkov.rankedchoice.stv.transfer.WigmTransfer;

/**
 * What to do with a ballot that ranks more than one candidate at the same position (an
 * "overvote", in the terms OpenSTV and most election law use).
 *
 * <ul>
 * <li>{@link #REJECT} — refuse to count the election at all.
 * <li>{@link #SPLIT} — count the tie as a genuine equal preference, dividing the ballot's
 * value between the tied candidates (see {@link StvCounter}'s class javadoc). Only defined
 * for {@link FractionalTransfer}.
 * <li>{@link #TRUNCATE} — the ballot stops at the first tied rank: everything before it
 * counts as usual, nothing from the tie onward does. A tie at the very first rank leaves an
 * empty ballot, which isn't counted as a valid vote at all — exactly the Scottish statute's
 * rule that a paper with the figure "1" against more than one candidate is void (SSI 2007/42,
 * Schedule 1, rule 43(1)(c)). Same as OpenSTV's "San Francisco" overvote cleaning.
 * <li>{@link #SKIP} — drop every tied rank and carry on with the ballot's later preferences.
 * Same as OpenSTV's "Cambridge" overvote cleaning.
 * </ul>
 *
 * <p>{@code TRUNCATE} and {@code SKIP} are pure ballot preprocessing, applied once before the
 * count starts (as OpenSTV's {@code getCleanBallots} does) rather than being a change to how
 * any transfer works — so unlike {@code SPLIT} they're valid with every transfer method.
 */
public enum TiedRankingPolicy {
    REJECT,
    SPLIT,
    TRUNCATE,
    SKIP;

    /**
     * The policy a ruleset gets when none is configured: {@code SPLIT} where splitting is
     * defined, {@code REJECT} otherwise.
     */
    public static TiedRankingPolicy defaultFor(TransferMethod transferMethod) {
        return splitDefinedFor(transferMethod) ? SPLIT : REJECT;
    }

    /**
     * Whether a transfer method has a defined rule for splitting a tied ballot's value — only
     * fractional transfer does. Deliberately an exhaustive switch over the sealed {@link
     * TransferMethod}, so adding a transfer method fails to compile here until someone decides.
     */
    private static boolean splitDefinedFor(TransferMethod transferMethod) {
        return switch (transferMethod) {
            case FractionalTransfer _ -> true;
            case WigmTransfer _, LastParcelTransfer _, LastParcelDeferredRoundingTransfer _ -> false;
        };
    }

    /**
     * Checks this policy can be used with {@code transferMethod} and {@code ballots}, then
     * returns the ballots the count should actually run on.
     */
    List<Ballot> prepare(List<Ballot> ballots, TransferMethod transferMethod) {
        return switch (this) {
            case REJECT -> {
                if (ballots.stream().anyMatch(TiedRankingPolicy::hasTie)) {
                    throw new UnsupportedOperationException("Ballots contain tied rankings; choose a tied-ranking "
                            + "policy (split, truncate, or skip) to count them");
                }
                yield ballots;
            }
            case SPLIT -> {
                if (!splitDefinedFor(transferMethod)) {
                    throw new UnsupportedOperationException("Splitting tied rankings is only defined for fractional "
                            + "transfer; WIGM and last-parcel-family transfer methods have no equal-preference "
                            + "splitting rule -- use truncate or skip instead");
                }
                yield ballots;
            }
            case TRUNCATE -> rewrite(ballots, true);
            case SKIP -> rewrite(ballots, false);
        };
    }

    private static boolean hasTie(Ballot ballot) {
        return ballot.ranking().stream().anyMatch(rank -> rank.size() > 1);
    }

    private static List<Ballot> rewrite(List<Ballot> ballots, boolean stopAtTie) {
        List<Ballot> result = new ArrayList<>(ballots.size());
        for (Ballot ballot : ballots) {
            if (!hasTie(ballot)) {
                result.add(ballot);
                continue;
            }
            List<Set<Candidate>> kept = new ArrayList<>();
            for (Set<Candidate> rank : ballot.ranking()) {
                if (rank.size() > 1) {
                    if (stopAtTie) {
                        break;
                    }
                    continue;
                }
                kept.add(rank);
            }
            result.add(new Ballot(kept, ballot.count()));
        }
        return result;
    }
}
