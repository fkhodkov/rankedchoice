package info.fkhodkov.rankedchoice.schulze;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/** Renders a {@link SchulzeResult} as the pairwise and strongest-path matrices plus the final ranking. */
public final class SchulzeTextReport {

    private SchulzeTextReport() {
    }

    public static String render(List<Candidate> candidates, SchulzeResult result) {
        int nameWidth = candidates.stream().mapToInt(c -> c.name().length()).max().orElse(0);

        StringBuilder text = new StringBuilder();
        text.append("Pairwise preferences (d):\n");
        appendMatrix(text, candidates, result.pairwiseWins(), nameWidth);
        text.append("\nStrongest paths (p):\n");
        appendMatrix(text, candidates, result.strongestPaths(), nameWidth);

        text.append("\nRanking:\n");
        int place = 1;
        for (Set<Candidate> group : result.ranking()) {
            String names = group.stream().map(Candidate::name).sorted().collect(Collectors.joining(" = "));
            text.append("  ").append(place).append(". ").append(names).append('\n');
            place += group.size();
        }
        return text.toString();
    }

    private static void appendMatrix(StringBuilder text, List<Candidate> candidates,
            Map<Candidate, Map<Candidate, BigInteger>> matrix, int nameWidth) {
        int numberWidth = candidates.stream()
                .flatMap(a -> candidates.stream().filter(b -> !b.equals(a)).map(b -> matrix.get(a).get(b)))
                .mapToInt(value -> value.toString().length())
                .max().orElse(1);
        for (Candidate a : candidates) {
            text.append("  ").append(padRight(a.name(), nameWidth)).append(':');
            for (Candidate b : candidates) {
                text.append(' ');
                if (a.equals(b)) {
                    text.append(padLeft("-", numberWidth));
                } else {
                    text.append(padLeft(matrix.get(a).get(b).toString(), numberWidth));
                }
            }
            text.append('\n');
        }
    }

    private static String padRight(String s, int width) {
        return s + " ".repeat(Math.max(0, width - s.length()));
    }

    private static String padLeft(String s, int width) {
        return " ".repeat(Math.max(0, width - s.length())) + s;
    }
}
