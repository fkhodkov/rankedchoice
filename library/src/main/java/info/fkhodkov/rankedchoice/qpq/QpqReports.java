package info.fkhodkov.rankedchoice.qpq;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.report.ReportMarkup;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.InformalBallots;
import info.fkhodkov.rankedchoice.stv.report.StvJsonReport;

/**
 * Renders a {@link QpqResult} as JSON, CSV or HTML, with the same figures as {@link
 * QpqTextReport}: each stage's quota and every hopeful candidate's quotient, in exact decimal
 * (see {@link Fraction#toDecimalString()}), highest quotient first.
 */
public final class QpqReports {

    private QpqReports() {
    }

    public record Action(String type, String candidate) {
    }

    public record Stage(int stage, boolean restarted, String quota, Map<String, String> quotients, Action action) {
    }

    public record Report(StvJsonReport.Informal informalBallots, List<Stage> stages, List<String> electedInOrder) {
    }

    /** The structured tree {@link #json} serializes. */
    public static Report toReport(QpqResult result) {
        InformalBallots informal = result.informal();
        return new Report(
                new StvJsonReport.Informal(informal.total(), informal.blank(), informal.tiedRankings(),
                        informal.tooFewPreferences()),
                result.rounds().stream().map(round -> new Stage(round.stageNumber(), round.restarted(),
                        round.quota().toDecimalString(), quotients(round), action(round.action()))).toList(),
                result.electedInOrder().stream().map(Candidate::name).toList());
    }

    public static String json(QpqResult result) {
        return ReportMarkup.json(toReport(result));
    }

    /** One row per hopeful candidate per stage; the stage's action on its candidate's row only. */
    public static String csv(QpqResult result) {
        StringBuilder csv = new StringBuilder("stage,restarted,candidate,quotient,quota,action,informal_ballots\n");
        String informal = result.informal().total().toString();
        for (QpqRoundRecord round : result.rounds()) {
            for (Map.Entry<String, String> quotient : quotients(round).entrySet()) {
                boolean acted = round.action().candidate().name().equals(quotient.getKey());
                csv.append(round.stageNumber()).append(',')
                        .append(round.restarted()).append(',')
                        .append(ReportMarkup.csvField(quotient.getKey())).append(',')
                        .append(quotient.getValue()).append(',')
                        .append(round.quota().toDecimalString()).append(',')
                        .append(acted ? action(round.action()).type() : "").append(',')
                        .append(informal).append('\n');
            }
        }
        return csv.toString();
    }

    public static String html(QpqResult result) {
        StringBuilder body = new StringBuilder();
        if (result.informal().total().signum() > 0) {
            body.append("<p>Informal ballots: ").append(result.informal().describe()).append("</p>\n");
        }
        for (QpqRoundRecord round : result.rounds()) {
            body.append("<h2>Stage ").append(round.stageNumber()).append(round.restarted() ? " (restarted)" : "")
                    .append("</h2>\n<p>Quota: ").append(round.quota().toDecimalString()).append("</p>\n");
            body.append("<table>\n<tr><th>Candidate</th><th>Quotient</th></tr>\n");
            for (Map.Entry<String, String> quotient : quotients(round).entrySet()) {
                boolean acted = round.action().candidate().name().equals(quotient.getKey());
                body.append(acted ? "<tr class=\"acted\">" : "<tr>")
                        .append("<td>").append(ReportMarkup.escapeHtml(quotient.getKey())).append("</td>")
                        .append("<td>").append(quotient.getValue()).append("</td></tr>\n");
            }
            body.append("</table>\n<p>").append(ReportMarkup.escapeHtml(round.action().candidate().name()))
                    .append(' ').append(action(round.action()).type()).append("</p>\n");
        }
        body.append("<p>Elected: ").append(ReportMarkup.escapeHtml(result.electedInOrder().stream()
                .map(Candidate::name).collect(Collectors.joining(", ")))).append("</p>\n");
        return ReportMarkup.htmlPage("QPQ Result", body.toString());
    }

    private static Map<String, String> quotients(QpqRoundRecord round) {
        Candidate.requireDistinctNames(round.quotients().keySet());
        Map<String, String> quotients = new LinkedHashMap<>();
        round.quotients().entrySet().stream()
                .sorted(Map.Entry.<Candidate, Fraction>comparingByValue().reversed())
                .forEach(entry -> quotients.put(entry.getKey().name(), entry.getValue().toDecimalString()));
        return quotients;
    }

    private static Action action(QpqAction action) {
        return switch (action) {
            case QpqAction.Elected(var candidate) -> new Action("elected", candidate.name());
            case QpqAction.Excluded(var candidate) -> new Action("excluded", candidate.name());
        };
    }
}
