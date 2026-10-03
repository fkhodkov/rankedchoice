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
 */
public record StvRuleset(
        Quota quota, TransferMethod transferMethod, RoundingRule roundingRule, TieBreakRule tieBreak,
        boolean batchExclusion, TiedRankingPolicy tiedRankings, int minimumPreferences,
        boolean deferSurpluses, boolean votesRequired) {

    public StvRuleset {
        if (minimumPreferences < 0) {
            throw new IllegalArgumentException("minimumPreferences must not be negative, got: " + minimumPreferences);
        }
    }

    public StvRuleset(Quota quota, TransferMethod transferMethod, RoundingRule roundingRule, TieBreakRule tieBreak,
            boolean batchExclusion, TiedRankingPolicy tiedRankings, int minimumPreferences) {
        this(quota, transferMethod, roundingRule, tieBreak, batchExclusion, tiedRankings, minimumPreferences, false,
                false);
    }

    public StvRuleset(Quota quota, TransferMethod transferMethod, RoundingRule roundingRule, TieBreakRule tieBreak,
            boolean batchExclusion, TiedRankingPolicy tiedRankings) {
        this(quota, transferMethod, roundingRule, tieBreak, batchExclusion, tiedRankings, 0);
    }

    public StvRuleset(Quota quota, TransferMethod transferMethod, RoundingRule roundingRule, TieBreakRule tieBreak,
            boolean batchExclusion) {
        this(quota, transferMethod, roundingRule, tieBreak, batchExclusion, TiedRankingPolicy.defaultFor(transferMethod));
    }

    public StvRuleset(Quota quota, TransferMethod transferMethod, RoundingRule roundingRule, TieBreakRule tieBreak) {
        this(quota, transferMethod, roundingRule, tieBreak, false);
    }
}
