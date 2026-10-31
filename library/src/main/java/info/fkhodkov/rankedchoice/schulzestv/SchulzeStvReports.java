package info.fkhodkov.rankedchoice.schulzestv;

import java.util.AbstractList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.report.ReportMarkup;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * Renders a {@link SchulzeStvResult} as JSON, CSV or HTML. JSON and CSV include every support
 * {@code N[committee; against]}, in decimal (see {@link Fraction#toDecimalString()}), since
 * they're meant for other programs; HTML shows them only while there are few enough to read
 * ({@link #HTML_TABLE_LIMIT}). A committee is its candidates' names, sorted.
 */
public final class SchulzeStvReports {

    /** Above this many supports, the HTML report leaves out the support table. */
    public static final int HTML_TABLE_LIMIT = 200;

    private SchulzeStvReports() {
    }

    public record Support(List<String> committee, String against, String support) {
    }

    public record Report(List<List<String>> winners, List<List<String>> potentialWinners,
            List<List<String>> candidateRanking, List<Support> supports) {
    }

    /**
     * The structured tree {@link #json} serializes. Its supports are made as they're read, one at
     * a time: there can be close to a million (24 candidates for 5 seats: 807,576), and held all
     * at once, as names and decimals, they took more memory than the count.
     */
    public static Report toReport(SchulzeStvResult result) {
        List<SchulzeStvResult.Support> supports = result.supports();
        return new Report(
                result.winners().stream().map(SchulzeStvReports::names).toList(),
                result.potentialWinners().stream().map(SchulzeStvReports::names).toList(),
                result.candidateRanking().ranking().stream().map(SchulzeStvReports::names).toList(),
                new AbstractList<>() {
                    @Override
                    public Support get(int index) {
                        SchulzeStvResult.Support support = supports.get(index);
                        return new Support(names(support.committee()), support.against().name(),
                                support.support().toDecimalString());
                    }

                    @Override
                    public int size() {
                        return supports.size();
                    }
                });
    }

    public static String json(SchulzeStvResult result) {
        return ReportMarkup.json(toReport(result));
    }

    /**
     * One row per support, then one row per potential winner with whether it won (support
     * columns empty), so a single file holds both.
     */
    public static String csv(SchulzeStvResult result) {
        StringBuilder csv = new StringBuilder("committee,against,support,potential_winner,winner\n");
        Report report = toReport(result);
        for (Support support : report.supports()) {
            csv.append(ReportMarkup.csvField(String.join(", ", support.committee()))).append(',')
                    .append(ReportMarkup.csvField(support.against())).append(',')
                    .append(support.support()).append(",,\n");
        }
        for (List<String> committee : report.potentialWinners()) {
            csv.append(ReportMarkup.csvField(String.join(", ", committee))).append(",,,true,")
                    .append(report.winners().contains(committee)).append('\n');
        }
        return csv.toString();
    }

    public static String html(SchulzeStvResult result) {
        Report report = toReport(result);
        StringBuilder body = new StringBuilder("<p>Winning committee: ").append(escape(report.winners().getFirst()));
        if (report.winners().size() > 1) {
            body.append(" (tied with ").append(report.winners().stream().skip(1).map(SchulzeStvReports::escape)
                    .collect(Collectors.joining(", "))).append(')');
        }
        body.append("</p>\n");
        if (report.potentialWinners().size() > 1) {
            body.append("<p>Potential winners (no committee beats them): ")
                    .append(report.potentialWinners().stream().map(SchulzeStvReports::escape)
                            .collect(Collectors.joining(", ")))
                    .append("</p>\n<h2>Single-winner Schulze ranking (tie-break)</h2>\n<ol>\n");
            int place = 1;
            for (List<String> group : report.candidateRanking()) {
                body.append("<li value=\"").append(place).append("\">")
                        .append(ReportMarkup.escapeHtml(String.join(" = ", group))).append("</li>\n");
                place += group.size();
            }
            body.append("</ol>\n");
        }
        if (!report.supports().isEmpty() && report.supports().size() <= HTML_TABLE_LIMIT) {
            body.append("<h2>Supports</h2>\n<p>How strongly each committee holds against each outsider.</p>\n")
                    .append("<table>\n<tr><th>Committee</th><th>Against</th><th>Support</th></tr>\n");
            for (Support support : report.supports()) {
                body.append("<tr><td>").append(escape(support.committee())).append("</td><td>")
                        .append(ReportMarkup.escapeHtml(support.against())).append("</td><td>")
                        .append(support.support()).append("</td></tr>\n");
            }
            body.append("</table>\n");
        } else if (report.supports().size() > HTML_TABLE_LIMIT) {
            body.append("<p>").append(report.supports().size()).append(" supports: too many to list here "
                    + "(the JSON and CSV reports have them all).</p>\n");
        }
        return ReportMarkup.htmlPage("Schulze STV Result", body.toString());
    }

    private static List<String> names(Set<Candidate> committee) {
        return committee.stream().map(Candidate::name).sorted().toList();
    }

    private static String escape(List<String> committee) {
        return ReportMarkup.escapeHtml("{" + String.join(", ", committee) + "}");
    }
}
