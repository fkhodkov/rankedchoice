package info.fkhodkov.rankedchoice.stv.report;

import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;

/**
 * Renders an {@link StvResult} as a human-readable, round-by-round plain-text report.
 * Candidate names and tallies are padded into columns that line up across every round, and
 * every number is shown with the same number of decimal places as {@code roundingRule}
 * itself truncates to — trailing zeros included — so the decimal points line up too. A
 * {@code truncate:N} rule renders e.g. the whole number 8434 as {@code "8434.000000"} for
 * {@code N = 6}; {@link Exact} has no such fixed precision, so figures fall back to however
 * many decimal places each one actually needs.
 */
public final class StvTextReport {

    private StvTextReport() {
    }

    public static String render(StvResult result, RoundingRule roundingRule) {
        OptionalInt decimalPlaces = decimalPlacesOf(roundingRule);

        int nameWidth = result.rounds().stream()
                .flatMap(round -> round.tallies().keySet().stream())
                .mapToInt(candidate -> candidate.name().length())
                .max().orElse(0);
        int numberWidth = result.rounds().stream()
                .flatMap(round -> round.tallies().values().stream())
                .mapToInt(value -> format(value, decimalPlaces).length())
                .max().orElse(0);

        StringBuilder text = new StringBuilder();
        text.append("Quota: ").append(format(result.quota(), decimalPlaces)).append("\n\n");

        for (RoundRecord round : result.rounds()) {
            text.append("Round ").append(round.roundNumber()).append('\n');
            for (Map.Entry<Candidate, Fraction> entry : sortedByTallyDescending(round)) {
                text.append("  ")
                        .append(padRight(entry.getKey().name(), nameWidth))
                        .append(": ")
                        .append(padLeft(format(entry.getValue(), decimalPlaces), numberWidth))
                        .append('\n');
            }
            text.append("  -> ").append(describe(round.action(), decimalPlaces)).append('\n');
            if (round.exhaustedVotes().signum() > 0) {
                text.append("  (exhausted so far: ")
                        .append(format(round.exhaustedVotes(), decimalPlaces))
                        .append(")\n");
            }
            text.append('\n');
        }

        text.append("Elected: ").append(result.electedInOrder().stream()
                .map(Candidate::name)
                .collect(Collectors.joining(", ")));
        text.append('\n');
        return text.toString();
    }

    /** {@code truncate:N}'s fixed decimal places to render every figure with, or empty for {@link Exact}. */
    private static OptionalInt decimalPlacesOf(RoundingRule roundingRule) {
        return switch (roundingRule) {
            case TruncateDecimal truncate -> OptionalInt.of(truncate.decimalPlaces());
            case Exact ignored -> OptionalInt.empty();
        };
    }

    private static String format(Fraction value, OptionalInt decimalPlaces) {
        return decimalPlaces.isPresent() ? value.toDecimalString(decimalPlaces.getAsInt()) : value.toDecimalString();
    }

    private static String padRight(String text, int width) {
        return text + " ".repeat(Math.max(0, width - text.length()));
    }

    private static String padLeft(String text, int width) {
        return " ".repeat(Math.max(0, width - text.length())) + text;
    }

    private static Iterable<Map.Entry<Candidate, Fraction>> sortedByTallyDescending(RoundRecord round) {
        return round.tallies().entrySet().stream()
                .sorted(Map.Entry.<Candidate, Fraction>comparingByValue().reversed())
                .toList();
    }

    private static String describe(RoundAction action, OptionalInt decimalPlaces) {
        return switch (action) {
            case RoundAction.Elected elected when elected.surplusTransferred().signum() > 0 ->
                    elected.candidate().name() + " elected (surplus "
                            + format(elected.surplusTransferred(), decimalPlaces) + " transferred)";
            case RoundAction.Elected elected -> elected.candidate().name() + " elected";
            case RoundAction.Eliminated eliminated -> eliminated.candidate().name() + " eliminated";
        };
    }
}
