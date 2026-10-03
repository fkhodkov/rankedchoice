package info.fkhodkov.rankedchoice.stv.config;

import info.fkhodkov.rankedchoice.core.config.ElectionConfig;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.TiedRankingPolicy;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.quota.HagenbachBischoffQuota;
import info.fkhodkov.rankedchoice.stv.quota.HareQuota;
import info.fkhodkov.rankedchoice.stv.quota.ImperialiQuota;
import info.fkhodkov.rankedchoice.stv.quota.Quota;
import info.fkhodkov.rankedchoice.stv.quota.RoundedUpQuota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
import info.fkhodkov.rankedchoice.stv.tiebreak.FirstPreferenceCountTieBreak;
import info.fkhodkov.rankedchoice.stv.tiebreak.PreviousRoundVotesTieBreak;
import info.fkhodkov.rankedchoice.stv.tiebreak.SeededRandomTieBreak;
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.LastParcelDeferredRoundingTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.LastParcelTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.TransferMethod;
import info.fkhodkov.rankedchoice.stv.transfer.WigmTransfer;
import java.util.List;
import java.util.Locale;

/**
 * Parses the small string DSL used to select STV building blocks from a config file or CLI
 * flag: quota is {@code droop|hare|hagenbach-bischoff|imperiali|droop-rounded:N}, transfer is
 * {@code fractional|wigm|last-parcel}, rounding is {@code exact|truncate:N}, and tie-break is
 * a comma-separated fallback chain, e.g. {@code previous-round,first-preference,declared} —
 * each rule in the chain falls back to the next when it can't resolve a tie itself.
 *
 * <p>{@code last-parcel} and {@code last-parcel-deferred-rounding} both examine only a
 * candidate's most recently arrived parcel, differing only in where the ruleset's rounding
 * rule gets applied — see {@link LastParcelTransfer} vs {@link
 * LastParcelDeferredRoundingTransfer}.
 */
public final class StvRulesetParser {

    private StvRulesetParser() {
    }

    public static StvRuleset parse(String quota, String transfer, String rounding, String tiebreak,
            List<Candidate> declaredOrder) {
        return parse(quota, transfer, rounding, tiebreak, null, declaredOrder);
    }

    /** As {@link #parse(String, String, String, String, List)}, with ERS97 rule 5.2.5's batch-exclusion enabled or not. */
    public static StvRuleset parse(String quota, String transfer, String rounding, String tiebreak,
            Boolean batchExclusion, List<Candidate> declaredOrder) {
        return parse(quota, transfer, rounding, tiebreak, batchExclusion, null, declaredOrder);
    }

    /** As above, plus a tied-ranking policy — see {@link #parseTiedRankings}. */
    public static StvRuleset parse(String quota, String transfer, String rounding, String tiebreak,
            Boolean batchExclusion, String tiedRankings, List<Candidate> declaredOrder) {
        return parse(quota, transfer, rounding, tiebreak, batchExclusion, tiedRankings, null, declaredOrder);
    }

    /**
     * As above, plus a minimum number of preferences for a ballot to be formal — see {@link
     * StvRuleset}. {@code null} means no minimum.
     */
    public static StvRuleset parse(String quota, String transfer, String rounding, String tiebreak,
            Boolean batchExclusion, String tiedRankings, Integer minimumPreferences, List<Candidate> declaredOrder) {
        return parse(quota, transfer, rounding, tiebreak, batchExclusion, tiedRankings, minimumPreferences, null, null,
                declaredOrder);
    }

    /** As above, plus ERS97's surplus deferral and votes required for election — see {@link StvRuleset}. */
    public static StvRuleset parse(String quota, String transfer, String rounding, String tiebreak,
            Boolean batchExclusion, String tiedRankings, Integer minimumPreferences, Boolean deferSurpluses,
            Boolean votesRequired, List<Candidate> declaredOrder) {
        TransferMethod transferMethod = parseTransfer(transfer);
        return new StvRuleset(
                parseQuota(quota), transferMethod, parseRounding(rounding),
                parseTieBreak(tiebreak, declaredOrder), batchExclusion != null && batchExclusion,
                parseTiedRankings(tiedRankings, transferMethod), minimumPreferences == null ? 0 : minimumPreferences,
                deferSurpluses != null && deferSurpluses, votesRequired != null && votesRequired);
    }

    /** Every STV setting a config file or bundled ruleset gives. */
    public static StvRuleset parse(ElectionConfig config, List<Candidate> declaredOrder) {
        return parse(config.quota(), config.transfer(), config.rounding(), config.tiebreak(), config.batchExclusion(),
                config.tiedRankings(), config.minimumPreferences(), config.deferSurpluses(), config.votesRequired(),
                declaredOrder);
    }

    /**
     * {@code reject|split|truncate|skip}; when absent, {@link
     * TiedRankingPolicy#defaultFor(TransferMethod)} applies (split under fractional transfer,
     * reject otherwise).
     */
    public static TiedRankingPolicy parseTiedRankings(String value, TransferMethod transferMethod) {
        return parseTiedRankings(value, TiedRankingPolicy.defaultFor(transferMethod));
    }

    /** As above, falling back to {@code defaultPolicy} — for counting methods other than classic STV. */
    public static TiedRankingPolicy parseTiedRankings(String value, TiedRankingPolicy defaultPolicy) {
        if (value == null) {
            return defaultPolicy;
        }
        return switch (normalize(value, "")) {
            case "reject" -> TiedRankingPolicy.REJECT;
            case "split" -> TiedRankingPolicy.SPLIT;
            case "truncate" -> TiedRankingPolicy.TRUNCATE;
            case "skip" -> TiedRankingPolicy.SKIP;
            default -> throw new IllegalArgumentException(
                    "Unknown tied-ranking policy: '" + value + "'; expected reject, split, truncate, or skip");
        };
    }

    public static Quota parseQuota(String value) {
        String normalized = normalize(value, "droop");
        if (normalized.startsWith("droop-rounded:")) {
            return new RoundedUpQuota(parsePlaces(normalized, "droop-rounded:"));
        }
        return switch (normalized) {
            case "droop" -> DroopQuota.INSTANCE;
            case "hare" -> HareQuota.INSTANCE;
            case "hagenbach-bischoff" -> HagenbachBischoffQuota.INSTANCE;
            case "imperiali" -> ImperialiQuota.INSTANCE;
            default -> throw new IllegalArgumentException("Unknown quota: '" + value + "'");
        };
    }

    public static TransferMethod parseTransfer(String value) {
        return switch (normalize(value, "fractional")) {
            case "fractional" -> FractionalTransfer.INSTANCE;
            case "wigm" -> WigmTransfer.INSTANCE;
            case "last-parcel" -> LastParcelTransfer.INSTANCE;
            case "last-parcel-deferred-rounding" -> LastParcelDeferredRoundingTransfer.INSTANCE;
            default -> throw new IllegalArgumentException("Unknown transfer method: '" + value + "'");
        };
    }

    public static RoundingRule parseRounding(String value) {
        String normalized = normalize(value, "exact");
        if (normalized.equals("exact")) {
            return Exact.INSTANCE;
        }
        if (normalized.startsWith("truncate:")) {
            return new TruncateDecimal(parsePlaces(normalized, "truncate:"));
        }
        throw new IllegalArgumentException("Unknown rounding rule: '" + value + "'");
    }

    public static TieBreakRule parseTieBreak(String value, List<Candidate> declaredOrder) {
        String[] chain = normalize(value, "declared").split(",");
        TieBreakRule rule = null;
        for (int i = chain.length - 1; i >= 0; i--) {
            rule = parseSingleTieBreak(chain[i].trim(), declaredOrder, rule);
        }
        return rule;
    }

    private static TieBreakRule parseSingleTieBreak(String token, List<Candidate> declaredOrder,
            TieBreakRule fallback) {
        if (token.startsWith("seeded-random:")) {
            return new SeededRandomTieBreak(parseSeed(token));
        }
        return switch (token) {
            case "declared" -> new DeclaredOrderTieBreak(declaredOrder);
            case "previous-round" -> new PreviousRoundVotesTieBreak(requireFallback(fallback, token));
            case "first-preference" ->
                new FirstPreferenceCountTieBreak(requireFallback(fallback, token));
            default -> throw new IllegalArgumentException("Unknown tie-break rule: '" + token + "'");
        };

    }

    private static TieBreakRule requireFallback(TieBreakRule fallback, String token) {
        if (fallback == null) {
            throw new IllegalArgumentException(
                    "'" + token + "' needs a fallback tie-break after it in the chain, e.g. '" + token
                            + ",declared'");
        }
        return fallback;
    }

    private static int parsePlaces(String value, String prefix) {
        String digits = value.substring(prefix.length());
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException("Invalid decimal places in '" + value + "'");
        }
    }

    private static long parseSeed(String token) {
        String digits = token.substring("seeded-random:".length());
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException("Invalid seed in '" + token + "'");
        }
    }

    private static String normalize(String value, String defaultValue) {
        return (value == null ? defaultValue : value).trim().toLowerCase(Locale.ROOT);
    }
}
