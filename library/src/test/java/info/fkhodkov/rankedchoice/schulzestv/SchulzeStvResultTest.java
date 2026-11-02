package info.fkhodkov.rankedchoice.schulzestv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/** A support keeps the committee it was given, and a count's supports share theirs. */
class SchulzeStvResultTest {

    private final Candidate a = Candidate.of("A");
    private final Candidate b = Candidate.of("B");
    private final Candidate c = Candidate.of("C");

    @Test
    void aSupportCopiesACommitteeItIsGiven() {
        Set<Candidate> committee = new LinkedHashSet<>(List.of(a, b));
        SchulzeStvResult.Support support = new SchulzeStvResult.Support(committee, c, Fraction.of(2));

        committee.add(c);

        assertThat(support.committee()).containsExactly(a, b);
        assertThatThrownBy(() -> support.committee().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aCountsSupportsShareOneCommitteeEach() {
        List<Candidate> candidates = List.of(a, b, c);
        SchulzeStvResult result = SchulzeStvCounter.count(candidates, List.of(
                new Ballot(List.of(Set.of(a), Set.of(b)), BigInteger.ONE)), 1);

        // Each committee of one stands against both others: two supports, one shared set.
        for (Candidate member : candidates) {
            List<Set<Candidate>> committees = result.supports().stream()
                    .map(SchulzeStvResult.Support::committee).filter(set -> set.equals(Set.of(member))).toList();
            assertThat(committees).hasSize(2);
            assertThat(committees.get(0)).isSameAs(committees.get(1));
        }
    }
}
