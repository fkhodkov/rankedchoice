package info.fkhodkov.rankedchoice.cpostv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.report.ReportMarkup;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.InformalBallots;
import info.fkhodkov.rankedchoice.stv.report.StvJsonReport;

/**
 * Renders a {@link CpoStvResult} as JSON, CSV or HTML. Unlike {@link CpoStvTextReport}, JSON and
 * CSV include every pairwise score (outcome A's score against outcome B), since they're meant
 * for other programs; HTML shows the score table only while it's small enough to read
 * ({@link #HTML_TABLE_LIMIT} outcomes). An outcome is its candidates' names, sorted.
 */
public final class CpoStvReports {

    /** Above this many outcomes, the HTML report leaves out the pairwise score table. */
    public static final int HTML_TABLE_LIMIT = 20;

    private CpoStvReports() {
    }

    public record Score(List<String> outcome, List<String> against, String score) {
    }

    public record Report(StvJsonReport.Informal informalBallots, List<List<String>> winners,
            List<List<List<String>>> ranking, List<Score> scores) {
    }

    /** The structured tree {@link #json} serializes. */
    public static Report toReport(CpoStvResult result) {
        InformalBallots informal = result.informal();
        Map<List<String>, Set<Candidate>> byNames = new LinkedHashMap<>();
        result.outcomeScores().keySet().forEach(outcome -> byNames.put(names(outcome), outcome));
        List<Score> scores = new ArrayList<>();
        for (List<String> outcome : rankedOutcomes(result)) {
            Map<Set<Candidate>, Fraction> row = result.outcomeScores().get(byNames.get(outcome));
            if (row == null) {
                continue;
            }
            for (Map.Entry<Set<Candidate>, Fraction> entry : row.entrySet()) {
                scores.add(new Score(outcome, names(entry.getKey()), entry.getValue().toDecimalString()));
            }
        }
        return new Report(
                new StvJsonReport.Informal(informal.total(), informal.blank(), informal.tiedRankings(),
                        informal.tooFewPreferences()),
                result.winners().stream().map(CpoStvReports::names).sorted(CpoStvReports::compare).toList(),
                result.ranking().stream().map(group -> group.stream().map(CpoStvReports::names)
                        .sorted(CpoStvReports::compare).toList()).toList(),
                scores);
    }

    public static String json(CpoStvResult result) {
        return ReportMarkup.json(toReport(result));
    }

    /** One row per ordered pair of outcomes: the first outcome's place and its score against the second. */
    public static String csv(CpoStvResult result) {
        StringBuilder csv = new StringBuilder("outcome,place,against,score,informal_ballots\n");
        Map<List<String>, Integer> places = places(result);
        String informal = result.informal().total().toString();
        Report report = toReport(result);
        for (Score score : report.scores()) {
            csv.append(ReportMarkup.csvField(String.join(", ", score.outcome()))).append(',')
                    .append(places.get(score.outcome())).append(',')
                    .append(ReportMarkup.csvField(String.join(", ", score.against()))).append(',')
                    .append(score.score()).append(',')
                    .append(informal).append('\n');
        }
        if (result.outcomeScores().isEmpty()) {
            // A single possible outcome: no comparisons, just the winner.
            for (List<String> winner : report.winners()) {
                csv.append(ReportMarkup.csvField(String.join(", ", winner))).append(",1,,,").append(informal)
                        .append('\n');
            }
        }
        return csv.toString();
    }

    public static String html(CpoStvResult result) {
        StringBuilder body = new StringBuilder();
        if (result.informal().total().signum() > 0) {
            body.append("<p>Informal ballots: ").append(result.informal().describe()).append("</p>\n");
        }
        Report report = toReport(result);
        body.append("<p>Winning outcome: ").append(escape(report.winners().getFirst()));
        if (report.winners().size() > 1) {
            body.append(" (tied with ").append(report.winners().size() - 1).append(" other outcome(s))");
        }
        body.append("</p>\n<h2>Full ranking</h2>\n<ol>\n");
        int place = 1;
        for (List<List<String>> group : report.ranking()) {
            body.append("<li value=\"").append(place).append("\">")
                    .append(group.stream().map(CpoStvReports::escape).collect(Collectors.joining(" = ")))
                    .append("</li>\n");
            place += group.size();
        }
        body.append("</ol>\n");
        List<List<String>> outcomes = rankedOutcomes(result);
        if (outcomes.size() > 1 && outcomes.size() <= HTML_TABLE_LIMIT) {
            Map<List<String>, Map<List<String>, String>> matrix = new LinkedHashMap<>();
            for (Score score : report.scores()) {
                matrix.computeIfAbsent(score.outcome(), _ -> new LinkedHashMap<>()).put(score.against(), score.score());
            }
            body.append("<h2>Scores</h2>\n<p>Each row's score against each column.</p>\n<table>\n<tr><th></th>");
            outcomes.forEach(outcome -> body.append("<th>").append(escape(outcome)).append("</th>"));
            body.append("</tr>\n");
            for (List<String> outcome : outcomes) {
                body.append("<tr><td>").append(escape(outcome)).append("</td>");
                for (List<String> against : outcomes) {
                    String score = outcome.equals(against) ? "-" : matrix.get(outcome).get(against);
                    body.append("<td>").append(score).append("</td>");
                }
                body.append("</tr>\n");
            }
            body.append("</table>\n");
        } else if (outcomes.size() > HTML_TABLE_LIMIT) {
            body.append("<p>").append(outcomes.size()).append(" outcomes: too many to tabulate every pairwise "
                    + "score here (the JSON and CSV reports have them all).</p>\n");
        }
        return ReportMarkup.htmlPage("CPO-STV Result", body.toString());
    }

    /** Every outcome, best first, each as its sorted candidate names. */
    private static List<List<String>> rankedOutcomes(CpoStvResult result) {
        return result.ranking().stream().flatMap(group -> group.stream().map(CpoStvReports::names)
                .sorted(CpoStvReports::compare)).toList();
    }

    private static Map<List<String>, Integer> places(CpoStvResult result) {
        Map<List<String>, Integer> places = new LinkedHashMap<>();
        int place = 1;
        for (Set<Set<Candidate>> group : result.ranking()) {
            for (Set<Candidate> outcome : group) {
                places.put(names(outcome), place);
            }
            place += group.size();
        }
        return places;
    }

    private static List<String> names(Set<Candidate> outcome) {
        return outcome.stream().map(Candidate::name).sorted().toList();
    }

    private static int compare(List<String> a, List<String> b) {
        return String.join(",", a).compareTo(String.join(",", b));
    }

    private static String escape(List<String> outcome) {
        return ReportMarkup.escapeHtml("{" + String.join(", ", outcome) + "}");
    }
}
