package info.fkhodkov.rankedchoice.cpostv;

import info.fkhodkov.rankedchoice.stv.TiedRankingPolicy;
import info.fkhodkov.rankedchoice.stv.quota.Quota;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.transfer.TransferMethod;

/**
 * The classic-STV building blocks CPO-STV's own "notional count" (see {@link CpoStvCounter})
 * reuses for each pairwise outcome comparison: how the quota is computed, and how a shared
 * candidate's surplus is transferred — plus how to treat tied rankings and short ballots (see
 * {@link info.fkhodkov.rankedchoice.stv.BallotFormality}). There's no {@code TieBreakRule}
 * here — outcomes are enumerated exhaustively (no elimination decision to break a tie for),
 * and two outcomes finishing a pairwise comparison exactly level is simply a tie in the
 * resulting beatpath ranking, same as any other genuine Condorcet tie.
 *
 * <p>{@link TiedRankingPolicy#SPLIT} isn't supported: no source defines how CPO-STV should
 * split a tied ballot. The default is {@link TiedRankingPolicy#REJECT}.
 */
public record CpoStvRuleset(Quota quota, TransferMethod transferMethod, RoundingRule roundingRule,
        TiedRankingPolicy tiedRankings, int minimumPreferences) {

    public CpoStvRuleset {
        if (minimumPreferences < 0) {
            throw new IllegalArgumentException("minimumPreferences must not be negative, got: " + minimumPreferences);
        }
    }

    public CpoStvRuleset(Quota quota, TransferMethod transferMethod, RoundingRule roundingRule) {
        this(quota, transferMethod, roundingRule, TiedRankingPolicy.REJECT, 0);
    }
}
