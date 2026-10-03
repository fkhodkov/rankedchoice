package info.fkhodkov.rankedchoice.schulzestv;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/**
 * Renders a {@link SchulzeStvResult} as the winning committee, and, when Stage 4 had to choose
 * among several potential winners, those committees and the single-winner ranking that chose.
 * Doesn't print the supports: there are {@code C(candidates, seats + 1) * (seats + 1)} of them
 * (the JSON and CSV reports have them all).
 */
public final class SchulzeStvTextReport {

    private SchulzeStvTextReport() {
    }

    public static String render(SchulzeStvResult result) {
        StringBuilder text = new StringBuilder("Winning committee: ").append(name(result.winners().getFirst()));
        if (result.winners().size() > 1) {
            text.append(" (tied with ").append(result.winners().size() - 1).append(" other committee(s): ")
                    .append(names(result.winners().subList(1, result.winners().size()))).append(')');
        }
        text.append('\n');
        if (result.potentialWinners().size() > 1) {
            text.append("\nPotential winners (no committee beats them): ").append(names(result.potentialWinners()))
                    .append("\nTie broken by the single-winner Schulze ranking:\n");
            int place = 1;
            for (Set<Candidate> group : result.candidateRanking().ranking()) {
                text.append("  ").append(place).append(". ")
                        .append(group.stream().map(Candidate::name).sorted().collect(Collectors.joining(" = ")))
                        .append('\n');
                place += group.size();
            }
        }
        return text.toString();
    }

    private static String names(List<Set<Candidate>> committees) {
        return committees.stream().map(SchulzeStvTextReport::name).collect(Collectors.joining(", "));
    }

    private static String name(Set<Candidate> committee) {
        return committee.stream().map(Candidate::name).sorted().collect(Collectors.joining(", ", "{", "}"));
    }
}
