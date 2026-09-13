package info.fkhodkov.rankedchoice.cpostv;

import java.util.Set;
import java.util.stream.Collectors;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/**
 * Renders a {@link CpoStvResult} as the winning outcome and the full outcome ranking. Doesn't
 * print the full pairwise score matrix — with {@code C(candidates, seats)} outcomes, that
 * grows quadratically and would usually be far too large to read.
 */
public final class CpoStvTextReport {

    private CpoStvTextReport() {
    }

    public static String render(CpoStvResult result) {
        StringBuilder text = new StringBuilder();
        text.append("Winning outcome: ").append(outcomeName(result.winners().iterator().next()));
        if (result.winners().size() > 1) {
            text.append(" (tied with ")
                    .append(result.winners().size() - 1)
                    .append(" other outcome(s))");
        }
        text.append("\n\nFull ranking:\n");
        int place = 1;
        for (Set<Set<Candidate>> group : result.ranking()) {
            String names = group.stream().map(CpoStvTextReport::outcomeName).sorted().collect(Collectors.joining(" = "));
            text.append("  ").append(place).append(". ").append(names).append('\n');
            place += group.size();
        }
        return text.toString();
    }

    private static String outcomeName(Set<Candidate> outcome) {
        return outcome.stream().map(Candidate::name).sorted().collect(Collectors.joining(", ", "{", "}"));
    }
}
