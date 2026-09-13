package info.fkhodkov.rankedchoice.cpostv;

import info.fkhodkov.rankedchoice.stv.quota.Quota;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.transfer.TransferMethod;

/**
 * The classic-STV building blocks CPO-STV's own "notional count" (see {@link CpoStvCounter})
 * reuses for each pairwise outcome comparison: how the quota is computed, and how a shared
 * candidate's surplus is transferred. There's no {@code TieBreakRule} here — outcomes are
 * enumerated exhaustively (no elimination decision to break a tie for), and two outcomes
 * finishing a pairwise comparison exactly level is simply a tie in the resulting beatpath
 * ranking, same as any other genuine Condorcet tie.
 */
public record CpoStvRuleset(Quota quota, TransferMethod transferMethod, RoundingRule roundingRule) {
}
