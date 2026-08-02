package info.fkhodkov.rankedchoice.stv.config;

import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.quota.HagenbachBischoffQuota;
import info.fkhodkov.rankedchoice.stv.quota.HareQuota;
import info.fkhodkov.rankedchoice.stv.quota.ImperialiQuota;
import info.fkhodkov.rankedchoice.stv.quota.Quota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
import info.fkhodkov.rankedchoice.stv.tiebreak.FirstPreferenceCountTieBreak;
import info.fkhodkov.rankedchoice.stv.tiebreak.PreviousRoundVotesTieBreak;
import info.fkhodkov.rankedchoice.stv.tiebreak.SeededRandomTieBreak;
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.TransferMethod;
import info.fkhodkov.rankedchoice.stv.transfer.WigmTransfer;
import java.util.List;
import java.util.Locale;

/**
 * Parses the small string DSL used to select STV building blocks from a config file or CLI
 * flag: quota is {@code droop|hare|hagenbach-bischoff|imperiali}, transfer is
 * {@code fractional|wigm}, rounding is {@code exact|truncate:N}, and tie-break is a
 * comma-separated fallback chain, e.g. {@code previous-round,first-preference,declared} —
 * each rule in the chain falls back to the next when it can't resolve a tie itself.
 */
public final class StvRulesetParser {

    private StvRulesetParser() {
    }

    public static StvRuleset parse(String quota, String transfer, String rounding, String tiebreak,
            List<Candidate> declaredOrder) {
        return new StvRuleset(
                parseQuota(quota), parseTransfer(transfer), parseRounding(rounding),
                parseTieBreak(tiebreak, declaredOrder));
    }

    public static Quota parseQuota(String value) {
        return switch (normalize(value, "droop")) {
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
            default -> throw new IllegalArgumentException("Unknown transfer method: '" + value + "'");
        };
    }

    public static RoundingRule parseRounding(String value) {
        String normalized = normalize(value, "exact");
        if (normalized.equals("exact")) {
            return Exact.INSTANCE;
        }
        if (normalized.startsWith("truncate:")) {
            return new TruncateDecimal(parsePlaces(normalized));
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

    private static int parsePlaces(String value) {
        String digits = value.substring("truncate:".length());
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
