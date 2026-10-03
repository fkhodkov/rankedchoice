package info.fkhodkov.rankedchoice.borda;

import java.util.ArrayList;
import java.util.List;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.report.ReportMarkup;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * Renders a {@link BordaResult} as JSON, CSV or HTML, with the same figures as {@link
 * BordaTextReport}: every candidate's score, highest first, in exact decimal (see {@link
 * Fraction#toDecimalString()}). Candidates on equal scores share a place.
 */
public final class BordaReports {

    private BordaReports() {
    }

    public record Entry(int place, String candidate, String score) {
    }

    public record Report(List<Entry> ranking) {
    }

    /** The structured tree {@link #json} serializes. */
    public static Report toReport(BordaResult result) {
        List<Entry> ranking = new ArrayList<>();
        Fraction previous = null;
        int place = 0;
        for (int i = 0; i < result.rankingBestToWorst().size(); i++) {
            Candidate candidate = result.rankingBestToWorst().get(i);
            Fraction score = result.scores().get(candidate);
            if (!score.equals(previous)) {
                place = i + 1;
                previous = score;
            }
            ranking.add(new Entry(place, candidate.name(), score.toDecimalString()));
        }
        return new Report(ranking);
    }

    public static String json(BordaResult result) {
        return ReportMarkup.json(toReport(result));
    }

    public static String csv(BordaResult result) {
        StringBuilder csv = new StringBuilder("place,candidate,score\n");
        for (Entry entry : toReport(result).ranking()) {
            csv.append(entry.place()).append(',').append(ReportMarkup.csvField(entry.candidate())).append(',')
                    .append(entry.score()).append('\n');
        }
        return csv.toString();
    }

    public static String html(BordaResult result) {
        StringBuilder body = new StringBuilder("<table>\n<tr><th>Place</th><th>Candidate</th><th>Score</th></tr>\n");
        for (Entry entry : toReport(result).ranking()) {
            body.append("<tr><td>").append(entry.place()).append("</td><td>")
                    .append(ReportMarkup.escapeHtml(entry.candidate())).append("</td><td>")
                    .append(entry.score()).append("</td></tr>\n");
        }
        body.append("</table>\n");
        return ReportMarkup.htmlPage("Borda Result", body.toString());
    }
}
