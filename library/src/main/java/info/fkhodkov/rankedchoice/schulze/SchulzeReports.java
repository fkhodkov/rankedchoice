package info.fkhodkov.rankedchoice.schulze;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.report.ReportMarkup;

/**
 * Renders a {@link SchulzeResult} as JSON, CSV or HTML, with the same figures as {@link
 * SchulzeTextReport}: the pairwise preferences {@code d} (ballots ranking the row candidate above
 * the column candidate), the strongest paths {@code p}, and the ranking, tied candidates sharing
 * a place.
 */
public final class SchulzeReports {

    private SchulzeReports() {
    }

    public record Report(List<String> candidates, Map<String, Map<String, BigInteger>> pairwise,
            Map<String, Map<String, BigInteger>> strongestPaths, List<List<String>> ranking) {
    }

    /** The structured tree {@link #json} serializes. */
    public static Report toReport(List<Candidate> candidates, SchulzeResult result) {
        return new Report(candidates.stream().map(Candidate::name).toList(),
                byName(candidates, result.pairwiseWins()), byName(candidates, result.strongestPaths()),
                result.ranking().stream().map(group -> group.stream().map(Candidate::name).sorted().toList()).toList());
    }

    public static String json(List<Candidate> candidates, SchulzeResult result) {
        return ReportMarkup.json(toReport(candidates, result));
    }

    /** One row per ordered pair of candidates. */
    public static String csv(List<Candidate> candidates, SchulzeResult result) {
        Map<Candidate, Integer> places = places(result);
        StringBuilder csv = new StringBuilder("candidate,place,against,pairwise,strongest_path\n");
        for (Candidate a : candidates) {
            for (Candidate b : candidates) {
                if (!a.equals(b)) {
                    csv.append(ReportMarkup.csvField(a.name())).append(',')
                            .append(places.get(a)).append(',')
                            .append(ReportMarkup.csvField(b.name())).append(',')
                            .append(result.pairwiseWins().get(a).get(b)).append(',')
                            .append(result.strongestPaths().get(a).get(b)).append('\n');
                }
            }
        }
        return csv.toString();
    }

    public static String html(List<Candidate> candidates, SchulzeResult result) {
        StringBuilder body = new StringBuilder("<h2>Ranking</h2>\n<ol>\n");
        int place = 1;
        for (Set<Candidate> group : result.ranking()) {
            body.append("<li value=\"").append(place).append("\">")
                    .append(ReportMarkup.escapeHtml(group.stream().map(Candidate::name).sorted()
                            .collect(Collectors.joining(" = "))))
                    .append("</li>\n");
            place += group.size();
        }
        body.append("</ol>\n");
        matrix(body, "Pairwise preferences (d)", candidates, result.pairwiseWins());
        matrix(body, "Strongest paths (p)", candidates, result.strongestPaths());
        return ReportMarkup.htmlPage("Schulze Result", body.toString());
    }

    private static void matrix(StringBuilder body, String title, List<Candidate> candidates,
            Map<Candidate, Map<Candidate, BigInteger>> values) {
        body.append("<h2>").append(title).append("</h2>\n<table>\n<tr><th></th>");
        candidates.forEach(c -> body.append("<th>").append(ReportMarkup.escapeHtml(c.name())).append("</th>"));
        body.append("</tr>\n");
        for (Candidate a : candidates) {
            body.append("<tr><td>").append(ReportMarkup.escapeHtml(a.name())).append("</td>");
            for (Candidate b : candidates) {
                body.append("<td>").append(a.equals(b) ? "-" : values.get(a).get(b).toString()).append("</td>");
            }
            body.append("</tr>\n");
        }
        body.append("</table>\n");
    }

    private static Map<String, Map<String, BigInteger>> byName(List<Candidate> candidates,
            Map<Candidate, Map<Candidate, BigInteger>> values) {
        Map<String, Map<String, BigInteger>> result = new LinkedHashMap<>();
        for (Candidate a : candidates) {
            Map<String, BigInteger> row = new LinkedHashMap<>();
            for (Candidate b : candidates) {
                if (!a.equals(b)) {
                    row.put(b.name(), values.get(a).get(b));
                }
            }
            result.put(a.name(), row);
        }
        return result;
    }

    private static Map<Candidate, Integer> places(SchulzeResult result) {
        Map<Candidate, Integer> places = new LinkedHashMap<>();
        int place = 1;
        for (Set<Candidate> group : result.ranking()) {
            for (Candidate candidate : group) {
                places.put(candidate, place);
            }
            place += group.size();
        }
        return places;
    }
}
