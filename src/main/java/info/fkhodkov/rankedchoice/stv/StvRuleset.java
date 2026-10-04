package info.fkhodkov.rankedchoice.stv;

import info.fkhodkov.rankedchoice.stv.quota.Quota;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;
import info.fkhodkov.rankedchoice.stv.transfer.TransferMethod;

/**
 * The combination of strategies that fully determines how an STV count is run. Real-world
 * rulesets (Scottish, ERS97, etc.) are just named presets of this combination — added in a
 * later phase alongside the additional quota/transfer options they need.
 *
 * <p>{@code batchExclusion} enables ERS97 rule 5.2.5's "exclude as many candidates together as
 * possible" provision: when several of the lowest continuing candidates' combined votes, plus
 * any deferred surplus, still couldn't reach the next candidate above them, they're all
 * excluded in the same stage rather than one at a time — confirmed against a real,
 * independent implementation (OpenSTV) reproducing the ERS97 rulebook's own model election
 * exactly, where excluding Wright and Glazier one at a time (this project's original,
 * un-batched behavior) changes the final winner from the rulebook's own Vicar to a wrong
 * Baron. Scottish/WIGM has no equivalent provision (confirmed against the actual statute,
 * SSI 2007/42 rule 50, which only ever excludes one candidate) and Hare-Clark/QPQ's own
 * source material doesn't document one either, so this defaults to {@code false} everywhere
 * except the bundled {@code ers97}/{@code ers76} rulesets.
 *
 * <p>{@code tiedRankings} says what to do with a ballot that ranks several candidates equally
 * — see {@link TiedRankingPolicy}. When not given, it defaults to {@link
 * TiedRankingPolicy#defaultFor}: split under fractional transfer, reject otherwise.
 *
 * <p>{@code minimumPreferences} is a formality rule: after tied rankings are dealt with, a
 * ballot ranking fewer candidates than this is informal — not counted, and not part of the
 * valid vote the quota is based on. It's capped at one fewer than the number of candidates,
 * since ranking every candidate but one already implies the last. {@code 0} (the default)
 * means no minimum. Tasmania's Electoral Act 2004 is the motivating case: an Assembly ballot
 * must number 1–7 "without omission or duplication" (s.102(1)) or it's informal
 * (s.103(1)(c)), and a council ballot needs 1–3, or 1–2 with 3 candidates, or just 1 with 2
 * (s.102(2)) — which the cap reproduces exactly.
 *
 * <p>{@code deferSurpluses} and {@code votesRequired} are two more ERS97 provisions, both off
 * except in the bundled {@code ers97}/{@code ers76} rulesets. With the first, pending surpluses
 * wait while an exclusion goes ahead, unless their total could change which candidate (or bulk
 * exclusion) comes next. With the second, a candidate is also elected on reaching the "votes
 * required for election": the total active vote divided by one more than the seats still to
 * fill, rounded up, which drops below the quota as votes are lost. Both as pyRCV2 counts ERS97
 * ("defer surpluses", and the "static with ERS97 rules" quota mode); the rulebook's own model
 * election count sheet shows both, its votes-required row included.
 *
 * <p>{@code surplusesInElectionOrder} transfers pending surpluses in the order their
 * candidates were elected rather than largest first, as the Australian Senate does; it only
 * matters where surpluses wait (the last-parcel and Senate transfer methods).
 *
 * <p>{@code electAsTheyStand} elects the last candidates on their votes as they stand, as the
 * AEC counts the Senate (Commonwealth Electoral Act 1918 s273(18) and (17)): when an exclusion
 * leaves as many continuing candidates as seats, they're elected without the excluded
 * candidate's papers being transferred; and when one seat is left between two continuing
 * candidates with no surplus waiting, the one with more votes is elected, below quota if need
 * be, without excluding the other. Otherwise the last exclusion is transferred, as Ireland's
 * and Scotland's count sheets show.
 *
 * <p>{@code electSureWinners} elects the leading candidates for the seats left as soon as none of
 * the rest can overtake them: the last of them has more votes than all the other continuing
 * candidates together, plus any surplus still to transfer. The count then ends, those surpluses
 * untransferred. The NSW Electoral Commission counts councillor elections this way, under quota
 * if need be; it's also how OpenTally ends a count. An exclusion that leaves as many candidates as
 * seats is otherwise transferred before they're elected.
 *
 * <p>{@code exclusionOrder} is the order an excluded candidate's papers move in, for the transfer
 * methods that exclude in steps: see {@link ExclusionOrder}.
 *
 * <p>{@code simultaneousSurpluses} transfers the surpluses of everyone elected in a round together,
 * as Portland, Oregon does (City Code 2.08.030 C.2.b(2)): a candidate one of them takes to quota is
 * elected in the next round, not there and then, so the round's other surpluses can still reach
 * them. Otherwise such a candidate is elected at once and passed by, as Scotland's rules require.
 *
 * <p>{@code mathematicalElimination} defeats, at the start of every round and before any surplus
 * moves, every candidate who can no longer be elected, as Minneapolis does (Code of Ordinances
 * 167.70 as enacted in 2006, and its counts of 2017 to 2025): the lowest candidates whose votes
 * together, with every surplus still to move, are less than the next candidate's, and those
 * ranked on too few ballots still in play to reach the candidate holding the last of the seats
 * left.
 * Only when nobody is defeated so does a surplus move, the largest one, one a round; until it
 * does, its candidate keeps receiving ballots. Only for the transfer methods that move a surplus
 * at once.
 *
 * <p>{@code quotaEachRound} recomputes the quota before every round and every surplus, on the
 * whole votes the candidates then hold, elected or not, as Alameda County's Dominion count did
 * Albany's in 2022 ("continuing ballots per round"): as ballots exhaust it falls, and a surplus
 * is what's above it then. A candidate already elected keeps what they were left with. Only for
 * the transfer methods that move a surplus at once; the result reports the first quota.
 */
public record StvRuleset(
        Quota quota, TransferMethod transferMethod, RoundingRule roundingRule, TieBreakRule tieBreak,
        boolean batchExclusion, TiedRankingPolicy tiedRankings, int minimumPreferences,
        boolean deferSurpluses, boolean votesRequired, boolean surplusesInElectionOrder,
        boolean electAsTheyStand, boolean electSureWinners, ExclusionOrder exclusionOrder,
        boolean simultaneousSurpluses, boolean mathematicalElimination, boolean quotaEachRound) {

    public StvRuleset {
        if (minimumPreferences < 0) {
            throw new IllegalArgumentException("minimumPreferences must not be negative, got: " + minimumPreferences);
        }
    }

    public StvRuleset(Quota quota, TransferMethod transferMethod, RoundingRule roundingRule, TieBreakRule tieBreak,
            boolean batchExclusion, TiedRankingPolicy tiedRankings, int minimumPreferences) {
        this(quota, transferMethod, roundingRule, tieBreak, batchExclusion, tiedRankings, minimumPreferences, false,
                false, false, false, false, ExclusionOrder.VALUE, false, false, false);
    }

    public StvRuleset(Quota quota, TransferMethod transferMethod, RoundingRule roundingRule, TieBreakRule tieBreak,
            boolean batchExclusion, TiedRankingPolicy tiedRankings) {
        this(quota, transferMethod, roundingRule, tieBreak, batchExclusion, tiedRankings, 0);
    }

    public StvRuleset(Quota quota, TransferMethod transferMethod, RoundingRule roundingRule, TieBreakRule tieBreak,
            boolean batchExclusion) {
        this(quota, transferMethod, roundingRule, tieBreak, batchExclusion,
                TiedRankingPolicy.defaultFor(transferMethod));
    }

    public StvRuleset(Quota quota, TransferMethod transferMethod, RoundingRule roundingRule, TieBreakRule tieBreak) {
        this(quota, transferMethod, roundingRule, tieBreak, false);
    }
}
