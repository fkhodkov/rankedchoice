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
 * Renders an {@link StvResult} as a single self-contained HTML page (inline CSS, no JS or
 * external assets) — one table per round, with the acted-on candidate's row highlighted, and
 * the same figures as {@link StvTextReport}.
 */
public final class StvHtmlReport {

    private StvHtmlReport() {
    }

    public static String render(StvResult result, RoundingRule roundingRule) {
        OptionalInt decimalPlaces = ReportFormatting.decimalPlacesOf(roundingRule);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n<title>STV Result</title>\n<style>\n")
                .append("body { font-family: sans-serif; }\n")
                .append("table { border-collapse: collapse; margin-bottom: 1em; }\n")
                .append("th, td { border: 1px solid #999; padding: 4px 10px; text-align: right; }\n")
                .append("th:first-child, td:first-child { text-align: left; }\n")
                .append("tr.acted { font-weight: bold; }\n")
                .append("</style>\n</head>\n<body>\n");
        html.append("<p>Quota: ").append(ReportFormatting.format(result.quota(), decimalPlaces)).append("</p>\n");

        for (RoundRecord round : result.rounds()) {
            html.append("<h2>Round ").append(round.roundNumber()).append("</h2>\n");
            html.append("<table>\n<tr><th>Candidate</th><th>Tally</th></tr>\n");
            for (Map.Entry<Candidate, Fraction> entry : ReportFormatting.sortedByTallyDescending(round)) {
                boolean acted = round.action().candidate().equals(entry.getKey());
                html.append(acted ? "<tr class=\"acted\">" : "<tr>")
                        .append("<td>").append(escape(entry.getKey().name())).append("</td>")
                        .append("<td>").append(ReportFormatting.format(entry.getValue(), decimalPlaces)).append("</td>")
                        .append("</tr>\n");
            }
            html.append("</table>\n");
            html.append("<p>").append(describe(round.action(), decimalPlaces)).append("</p>\n");
            if (round.exhaustedVotes().signum() > 0) {
                html.append("<p>Exhausted so far: ")
                        .append(ReportFormatting.format(round.exhaustedVotes(), decimalPlaces))
                        .append("</p>\n");
            }
        }

        html.append("<p>Elected: ");
        boolean first = true;
        for (Candidate candidate : result.electedInOrder()) {
            if (!first) {
                html.append(", ");
            }
            html.append(escape(candidate.name()));
            first = false;
        }
        html.append("</p>\n</body>\n</html>\n");
        return html.toString();
    }

    private static String describe(RoundAction action, OptionalInt decimalPlaces) {
        return switch (action) {
            case RoundAction.Elected elected when elected.surplusTransferred().signum() > 0 ->
                    escape(elected.candidate().name()) + " elected (surplus "
                            + ReportFormatting.format(elected.surplusTransferred(), decimalPlaces) + " transferred)";
            case RoundAction.Elected elected -> escape(elected.candidate().name()) + " elected";
            case RoundAction.Eliminated eliminated -> escape(eliminated.candidate().name()) + " eliminated";
        };
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
