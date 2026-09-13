package info.fkhodkov.rankedchoice.borda;

import info.fkhodkov.rankedchoice.core.model.Candidate;

/** Renders a {@link BordaResult} as a simple score table, highest to lowest. */
public final class BordaTextReport {

    private BordaTextReport() {
    }

    public static String render(BordaResult result) {
        int nameWidth = result.rankingBestToWorst().stream().mapToInt(c -> c.name().length()).max().orElse(0);
        int numberWidth = result.scores().values().stream()
                .mapToInt(score -> score.toDecimalString().length())
                .max().orElse(1);

        StringBuilder text = new StringBuilder();
        for (Candidate candidate : result.rankingBestToWorst()) {
            text.append(padRight(candidate.name(), nameWidth))
                    .append(": ")
                    .append(padLeft(result.scores().get(candidate).toDecimalString(), numberWidth))
                    .append('\n');
        }
        return text.toString();
    }

    private static String padRight(String s, int width) {
        return s + " ".repeat(Math.max(0, width - s.length()));
    }

    private static String padLeft(String s, int width) {
        return " ".repeat(Math.max(0, width - s.length())) + s;
    }
}
