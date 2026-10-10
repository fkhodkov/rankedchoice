package info.fkhodkov.rankedchoice.schulzestv;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.report.ReportMarkup;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * Renders a {@link SchulzeProportionalResult} as text, JSON, CSV or HTML. Text and HTML give the
 * list, and for any place with several potential winners, those and the tie-break; JSON and CSV
 * also give every comparison H[b][c] behind every place, in decimal (see {@link
 * Fraction#toDecimalString()}), since they're meant for other programs.
 */
public final class SchulzeProportionalReports {

    private SchulzeProportionalReports() {
    }

    public record Comparison(String candidate, String against, String support) {
    }

    public record Place(int place, String candidate, List<String> potentialWinners, List<Comparison> comparisons) {
    }

    public record Report(List<String> ranking, List<Place> places, List<List<String>> tieBreakRanking) {
    }

    /** The structured tree {@link #json} serializes. */
    public static Report toReport(SchulzeProportionalResult result) {
        List<Place> places = new ArrayList<>();
        for (SchulzeProportionalResult.Place place : result.places()) {
            List<Comparison> comparisons = new ArrayList<>();
            for (Map.Entry<Candidate, Map<Candidate, Fraction>> row : place.comparisons().entrySet()) {
                for (Map.Entry<Candidate, Fraction> cell : row.getValue().entrySet()) {
                    comparisons.add(new Comparison(row.getKey().name(), cell.getKey().name(),
                            cell.getValue().toDecimalString()));
                }
            }
            places.add(new Place(place.place(), place.candidate().name(),
                    place.potentialWinners().stream().map(Candidate::name).toList(), comparisons));
        }
        return new Report(result.ranking().stream().map(Candidate::name).toList(), places,
                result.tieBreakRanking().ranking().stream()
                        .map(group -> group.stream().map(Candidate::name).sorted().toList()).toList());
    }

    public static String text(SchulzeProportionalResult result) {
        StringBuilder text = new StringBuilder("Schulze proportional ranking:\n");
        boolean tieBroken = false;
        for (SchulzeProportionalResult.Place place : result.places()) {
            text.append(String.format("%3d. %s", place.place(), place.candidate().name()));
            if (place.potentialWinners().size() > 1) {
                tieBroken = true;
                text.append("  (tie-break among ").append(names(place.potentialWinners())).append(')');
            }
            text.append('\n');
        }
        if (tieBroken) {
            text.append("\nTies broken by the single-winner Schulze ranking:\n");
            appendRanking(text, result.tieBreakRanking().ranking(), "  ");
        }
        return text.toString();
    }

    public static String json(SchulzeProportionalResult result) {
        return ReportMarkup.json(toReport(result));
    }

    /** One row per comparison, place by place, then one row per place with who took it. */
    public static String csv(SchulzeProportionalResult result) {
        StringBuilder csv = new StringBuilder("place,candidate,against,support,placed,potential_winner\n");
        Report report = toReport(result);
        for (Place place : report.places()) {
            for (Comparison comparison : place.comparisons()) {
                csv.append(place.place()).append(',').append(ReportMarkup.csvField(comparison.candidate()))
                        .append(',').append(ReportMarkup.csvField(comparison.against())).append(',')
                        .append(comparison.support()).append(",,\n");
            }
        }
        for (Place place : report.places()) {
            for (String candidate : place.potentialWinners()) {
                csv.append(place.place()).append(',').append(ReportMarkup.csvField(candidate)).append(",,,")
                        .append(candidate.equals(place.candidate())).append(",true\n");
            }
        }
        return csv.toString();
    }

    public static String html(SchulzeProportionalResult result) {
        StringBuilder body = new StringBuilder("<ol>\n");
        boolean tieBroken = false;
        for (SchulzeProportionalResult.Place place : result.places()) {
            body.append("<li>").append(ReportMarkup.escapeHtml(place.candidate().name()));
            if (place.potentialWinners().size() > 1) {
                tieBroken = true;
                body.append(" (tie-break among ")
                        .append(ReportMarkup.escapeHtml(names(place.potentialWinners()))).append(')');
            }
            body.append("</li>\n");
        }
        body.append("</ol>\n");
        if (tieBroken) {
            body.append("<h2>Single-winner Schulze ranking (tie-break)</h2>\n<ol>\n");
            int at = 1;
            for (Set<Candidate> group : result.tieBreakRanking().ranking()) {
                body.append("<li value=\"").append(at).append("\">").append(ReportMarkup.escapeHtml(
                        group.stream().map(Candidate::name).sorted().collect(Collectors.joining(" = "))))
                        .append("</li>\n");
                at += group.size();
            }
            body.append("</ol>\n");
        }
        return ReportMarkup.htmlPage("Schulze Proportional Ranking", body.toString());
    }

    private static void appendRanking(StringBuilder text, List<Set<Candidate>> ranking, String indent) {
        int at = 1;
        for (Set<Candidate> group : ranking) {
            text.append(indent).append(at).append(". ")
                    .append(group.stream().map(Candidate::name).sorted().collect(Collectors.joining(" = ")))
                    .append('\n');
            at += group.size();
        }
    }

    private static String names(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::name).collect(Collectors.joining(", "));
    }
}
