package info.fkhodkov.rankedchoice.cpostv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/** A result built from a caller's own sets keeps what it was given, outcomes included. */
class CpoStvResultTest {

    private final Candidate a = Candidate.of("A");
    private final Candidate b = Candidate.of("B");

    @Test
    void copiesEveryOutcome() {
        Set<Candidate> first = new LinkedHashSet<>(List.of(a));
        Set<Candidate> second = new LinkedHashSet<>(List.of(b));
        Map<Set<Candidate>, Map<Set<Candidate>, Fraction>> scores = new LinkedHashMap<>();
        scores.put(first, new LinkedHashMap<>(Map.of(second, Fraction.of(3))));
        scores.put(second, new LinkedHashMap<>(Map.of(first, Fraction.of(2))));
        CpoStvResult result = new CpoStvResult(scores, List.of(new LinkedHashSet<>(List.of(first)),
                new LinkedHashSet<>(List.of(second))));

        // The review's case: clearing the winning outcome changed the winner and lost its scores.
        assertThatThrownBy(() -> result.winners().iterator().next().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.outcomeScores().keySet().iterator().next().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        first.clear();
        second.add(a);

        assertThat(result.winners()).containsExactly(Set.of(a));
        assertThat(result.outcomeScores().get(Set.of(a))).containsExactly(Map.entry(Set.of(b), Fraction.of(3)));
        assertThat(CpoStvReports.json(result)).contains("\"winners\" : [ [ \"A\" ] ]");
    }
}
