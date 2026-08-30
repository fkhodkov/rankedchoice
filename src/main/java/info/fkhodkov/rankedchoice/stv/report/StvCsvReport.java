package info.fkhodkov.rankedchoice.stv.report;

import java.util.Map;
import java.util.OptionalInt;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * Renders an {@link StvResult} as CSV, one row per candidate per round — the same figures as
 * {@link StvTextReport}, reshaped for spreadsheets. Each round's {@link RoundAction} is
 * recorded only on its own candidate's row ({@code action} is blank for every other candidate
 * that round); {@code exhausted_votes} repeats the round's running total on every row of that
 * round, since CSV has no notion of a merged cell.
 */
public final class StvCsvReport {

    private StvCsvReport() {
    }

    public static String render(StvResult result, RoundingRule roundingRule) {
        OptionalInt decimalPlaces = ReportFormatting.decimalPlacesOf(roundingRule);
        StringBuilder csv = new StringBuilder("round,candidate,tally,action,exhausted_votes\n");
        for (RoundRecord round : result.rounds()) {
            String exhausted = ReportFormatting.format(round.exhaustedVotes(), decimalPlaces);
            for (Map.Entry<Candidate, Fraction> entry : ReportFormatting.sortedByTallyDescending(round)) {
                csv.append(round.roundNumber()).append(',')
                        .append(field(entry.getKey().name())).append(',')
                        .append(ReportFormatting.format(entry.getValue(), decimalPlaces)).append(',')
                        .append(field(actionFor(round.action(), entry.getKey(), decimalPlaces))).append(',')
                        .append(exhausted).append('\n');
            }
        }
        return csv.toString();
    }

    private static String actionFor(RoundAction action, Candidate candidate, OptionalInt decimalPlaces) {
        if (!action.candidate().equals(candidate)) {
            return "";
        }
        return switch (action) {
            case RoundAction.Elected elected when elected.surplusTransferred().signum() > 0 ->
                    "elected (surplus " + ReportFormatting.format(elected.surplusTransferred(), decimalPlaces) + ")";
            case RoundAction.Elected ignored -> "elected";
            case RoundAction.Eliminated ignored -> "eliminated";
        };
    }

    /** RFC 4180 quoting, applied only when a field actually needs it (comma, quote, or newline). */
    private static String field(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
