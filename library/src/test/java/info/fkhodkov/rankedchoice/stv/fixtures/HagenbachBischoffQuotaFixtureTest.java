package info.fkhodkov.rankedchoice.stv.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.config.StvRulesetParser;

/**
 * Every other fixture test in this package runs the (by far the most common in practice)
 * Droop quota — {@link QuotaTest} unit-tests the arithmetic of {@code hare}, {@code
 * hagenbach-bischoff}, and {@code imperiali} in isolation, but none of the three is exercised
 * end-to-end through {@link StvCounter}. This fixture closes that gap for Hagenbach-Bischoff,
 * using {@code testdata/wikipedia/wikipedia-andrea-brad-carter-delilah.blt} — a ballot-level
 * reconstruction of Wikipedia's "Counting single transferable votes" worked example, whose
 * own narrative computes the quota as a plain {@code 57/(2+1) = 19}, i.e. Hagenbach-Bischoff
 * (unrounded, no "+1"), not the standard Droop quota this project defaults to. See that file's
 * header comment for the reconstruction's provenance and caveats.
 *
 * @see info.fkhodkov.rankedchoice.stv.quota.QuotaTest
 */
class HagenbachBischoffQuotaFixtureTest {

    @Test
    void reproducesTheWikipediaWorkedExampleUnderItsOwnQuotaFormula() {
        ParsedElection election = BltParser.parse(
                Path.of("src/test/resources/testdata/wikipedia/wikipedia-andrea-brad-carter-delilah.blt"));
        List<Candidate> candidates = election.candidates();

        StvRuleset ruleset = StvRulesetParser.parse("hagenbach-bischoff", null, null, null, candidates);
        StvResult result = StvCounter.count(candidates, election.ballots(), election.seats(), ruleset);

        Candidate andrea = candidateNamed(candidates, "Andrea");
        Candidate brad = candidateNamed(candidates, "Brad");
        Candidate carter = candidateNamed(candidates, "Carter");
        Candidate delilah = candidateNamed(candidates, "Delilah");

        assertThat(result.quota()).isEqualTo(Fraction.of(19));
        assertThat(result.rounds()).hasSize(3);

        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.tallies())
                .containsEntry(andrea, Fraction.of(40))
                .containsEntry(delilah, Fraction.of(17))
                .containsEntry(brad, Fraction.ZERO)
                .containsEntry(carter, Fraction.ZERO);
        // Andrea's 40 clears the 19-vote quota by 21, all reconstructed ballots specify a
        // next preference (either Brad or Carter), so the full surplus transfers: 24 ballots
        // ranked Andrea,Brad each carry 24 * (21/40) = 12.6 to Brad, and 16 ranked
        // Andrea,Carter,Brad each carry 16 * (21/40) = 8.4 to Carter.
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(andrea, Fraction.of(21)));

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies())
                .containsEntry(delilah, Fraction.of(17))
                .containsEntry(brad, Fraction.of(63, 5)) // 12.6
                .containsEntry(carter, Fraction.of(42, 5)); // 8.4
        assertThat(round2.action()).isEqualTo(new RoundAction.Eliminated(carter));

        // Round 3 — Carter eliminated, her 8.4 votes (all ranked ...,Carter,Brad) move to
        // Brad in full: 12.6 + 8.4 = 21, clearing the 19-vote quota, for the final seat. The
        // count ends there, so his surplus of 2 isn't transferred.
        RoundRecord round3 = result.rounds().get(2);
        assertThat(round3.tallies())
                .containsEntry(brad, Fraction.of(21))
                .containsEntry(delilah, Fraction.of(17));
        assertThat(round3.action()).isEqualTo(new RoundAction.Elected(brad, Fraction.ZERO));

        assertThat(result.electedInOrder()).containsExactly(andrea, brad);
    }

    private static Candidate candidateNamed(List<Candidate> candidates, String name) {
        return candidates.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No candidate named " + name));
    }
}
