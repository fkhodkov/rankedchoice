package info.fkhodkov.rankedchoice.stv.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.config.ConfigLoader;
import info.fkhodkov.rankedchoice.core.config.ElectionConfig;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.config.StvRulesetParser;

/**
 * Validates the {@code ers97} ruleset against the Electoral Reform Society's own worked
 * "model election" from the ERS97 rulebook (11 candidates, 6 seats, 753 votes) — not a
 * self-generated fixture; the ballot data and expected figures come from an independent,
 * third-party STV implementation's own correctness test (pyRCV2, see the citation comment in
 * {@code testdata/ers97/ers97-model-election.blt}).
 *
 * <p>Round 3 (Monk's elimination fully processed) specifically exercises the "exclusion by
 * value, largest value-group first, with newly quota-crossing candidates frozen mid-way"
 * rule: Duke crosses quota after Monk's first (larger) value-group but before her second,
 * and a naive single-shot elimination would wrongly credit him with both — this is the exact
 * scenario that motivated splitting eliminations by value-group in {@code StvCounter}, and
 * this test is what caught the original bug (Duke landing on 108.89 instead of the rulebook's
 * 108.68).
 *
 * <p>Rounds 4-6 specifically exercise rule 5.2.5's batch-exclusion, where Wright and Glazier
 * are excluded together as one stage rather than one at a time — the original, un-batched
 * behavior instead let Baron overtake Vicar for the final seat, a real bug caught by
 * cross-checking against an independent implementation (OpenSTV) reproducing this same model
 * election.
 *
 * <p>This still isn't a full round-by-round replay, though: real ERS97 counting also defers a
 * surplus transfer whenever a vote-gap safety check proves it can't affect any exclusion
 * decision — not implemented here — so beyond round 3, this engine's deferred-surplus timing
 * diverges from the rulebook's own stage sequence, even though every individual tally, once
 * transferred, and the final elected set, still match exactly (see the fixture file's
 * citation comment for detail).
 */
class Ers97ModelElectionFixtureTest {

    @Test
    void reproducesTheErs97ModelElectionResultExactly() {
        ParsedElection election = BltParser.parse(readLines("/testdata/ers97/ers97-model-election.blt"));
        List<Candidate> candidates = election.candidates();

        ElectionConfig ers97 = ConfigLoader.loadResource("/rulesets/ers97.yaml");
        StvRuleset ruleset = StvRulesetParser.parse(
                ers97.quota(), ers97.transfer(), ers97.rounding(), ers97.tiebreak(), ers97.batchExclusion(),
                candidates);

        StvResult result = StvCounter.count(candidates, election.ballots(), election.seats(), ruleset);

        Candidate smith = candidateNamed(candidates, "Smith");
        Candidate carpenter = candidateNamed(candidates, "Carpenter");
        Candidate wright = candidateNamed(candidates, "Wright");
        Candidate glazier = candidateNamed(candidates, "Glazier");
        Candidate duke = candidateNamed(candidates, "Duke");
        Candidate prince = candidateNamed(candidates, "Prince");
        Candidate baron = candidateNamed(candidates, "Baron");
        Candidate abbot = candidateNamed(candidates, "Abbot");
        Candidate vicar = candidateNamed(candidates, "Vicar");
        Candidate monk = candidateNamed(candidates, "Monk");
        Candidate freeman = candidateNamed(candidates, "Freeman");

        assertThat(result.quota()).isEqualTo(Fraction.of(5379, 50)); // 107.58
        assertThat(result.rounds().getFirst().tallies())
                .containsEntry(smith, Fraction.of(134))
                .containsEntry(duke, Fraction.of(105))
                .containsEntry(prince, Fraction.of(91))
                .containsEntry(freeman, Fraction.of(90))
                .containsEntry(carpenter, Fraction.of(81))
                .containsEntry(baron, Fraction.of(64))
                .containsEntry(abbot, Fraction.of(59))
                .containsEntry(vicar, Fraction.of(55))
                .containsEntry(wright, Fraction.of(27))
                .containsEntry(glazier, Fraction.of(24))
                .containsEntry(monk, Fraction.of(23));
        assertThat(result.rounds().getFirst().action())
                .isEqualTo(new RoundAction.Elected(smith, Fraction.of(1321, 50))); // surplus 26.42
        assertThat(result.electedInOrder())
                .containsExactlyInAnyOrder(smith, duke, carpenter, prince, freeman, vicar);

        // Round 2 (Smith's surplus transferred): matches the rulebook's stage 2 exactly.
        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies())
                .containsEntry(duke, Fraction.of(5334, 50)) // 106.68
                .containsEntry(freeman, Fraction.of(4689, 50)) // 93.78
                .containsEntry(carpenter, Fraction.of(1767, 20)) // 88.35
                .containsEntry(abbot, Fraction.of(1496, 25)) // 59.84
                .containsEntry(wright, Fraction.of(129, 4)) // 32.25
                .containsEntry(glazier, Fraction.of(3051, 100)) // 30.51
                .containsEntry(monk, Fraction.of(1171, 50)); // 23.42
        assertThat(round2.action()).isEqualTo(new RoundAction.Eliminated(monk));

        // Round 3 (Monk fully eliminated, both value-groups applied): matches the rulebook's
        // stage 4 exactly -- Duke's 108.68 is the figure the value-group-split fix exists for.
        RoundRecord round3 = result.rounds().get(2);
        assertThat(round3.tallies())
                .containsEntry(duke, Fraction.of(2717, 25)) // 108.68
                .containsEntry(freeman, Fraction.of(4789, 50)) // 95.78
                .containsEntry(prince, Fraction.of(91))
                .containsEntry(carpenter, Fraction.of(1767, 20)) // 88.35
                .containsEntry(vicar, Fraction.of(6921, 100)) // 69.21
                .containsEntry(abbot, Fraction.of(1621, 25)) // 64.84
                .containsEntry(baron, Fraction.of(6421, 100)) // 64.21
                .containsEntry(wright, Fraction.of(129, 4)) // 32.25
                .containsEntry(glazier, Fraction.of(3051, 100)); // 30.51
        assertThat(round3.action()).isEqualTo(new RoundAction.Elected(duke, Fraction.of(11, 10))); // surplus 1.10

        // Rounds 4-5 (Wright and Glazier excluded together as one batch, per rule 5.2.5:
        // their combined votes plus Duke's deferred 1.10 surplus still don't reach Baron, the
        // next candidate above them) -- matches the rulebook's own model election exactly
        // (confirmed against an independent implementation, OpenSTV): batching these two is
        // what turns the final winner from a wrong Baron into the rulebook's own Vicar.
        RoundRecord round4 = result.rounds().get(3);
        assertThat(round4.action()).isEqualTo(new RoundAction.Eliminated(glazier));
        RoundRecord round5 = result.rounds().get(4);
        assertThat(round5.action()).isEqualTo(new RoundAction.Eliminated(wright));
        // Both share the same pre-exclusion tally snapshot -- they're decided together, as one
        // stage, not as two independent one-at-a-time eliminations.
        assertThat(round4.tallies()).isEqualTo(round5.tallies());
        assertThat(round4.tallies())
                .containsEntry(carpenter, Fraction.of(1767, 20)) // 88.35
                .containsEntry(prince, Fraction.of(91))
                .containsEntry(baron, Fraction.of(6421, 100)) // 64.21
                .containsEntry(abbot, Fraction.of(1621, 25)) // 64.84
                .containsEntry(vicar, Fraction.of(6921, 100)) // 69.21
                .containsEntry(freeman, Fraction.of(4789, 50)); // 95.78

        // Round 6 (Wright and Glazier's pooled ballots fully transferred, both value-groups
        // applied; Carpenter crosses quota): matches the rulebook's own merged substage-1 +
        // substage-2 figures exactly -- Carpenter's 122.35 is the figure this fix exists for
        // (the un-batched, one-at-a-time original behavior instead let Baron overtake Vicar
        // here, changing the eventual winner).
        RoundRecord round6 = result.rounds().get(5);
        assertThat(round6.tallies())
                .containsEntry(carpenter, Fraction.of(2447, 20)) // 122.35
                .containsEntry(prince, Fraction.of(10431, 100)) // 104.31
                .containsEntry(baron, Fraction.of(3413, 50)) // 68.26
                .containsEntry(abbot, Fraction.of(671, 10)) // 67.10
                .containsEntry(vicar, Fraction.of(7131, 100)) // 71.31
                .containsEntry(freeman, Fraction.of(4831, 50)); // 96.62
        assertThat(round6.action()).isEqualTo(new RoundAction.Elected(carpenter, Fraction.of(1477, 100))); // surplus 14.77
    }

    /**
     * The rulebook's "Non-transferable" row, which counts exhausted votes and votes lost to
     * rounding together: 0.59 after Smith's surplus (stage 2) and still after Monk's exclusion
     * (stage 4). Smith's 11 papers with no further preference stay with him as part of his
     * quota; only the 0.59 his rounded transfer value fails to pass on is lost.
     */
    @Test
    void nonTransferableVotesMatchTheRulebook() {
        ParsedElection election = BltParser.parse(readLines("/testdata/ers97/ers97-model-election.blt"));
        List<Candidate> candidates = election.candidates();
        ElectionConfig ers97 = ConfigLoader.loadResource("/rulesets/ers97.yaml");
        StvRuleset ruleset = StvRulesetParser.parse(
                ers97.quota(), ers97.transfer(), ers97.rounding(), ers97.tiebreak(), ers97.batchExclusion(),
                candidates);

        StvResult result = StvCounter.count(candidates, election.ballots(), election.seats(), ruleset);

        for (RoundRecord round : result.rounds().subList(1, 3)) { // rulebook stages 2 and 4
            assertThat(round.exhaustedVotes()).isEqualTo(Fraction.ZERO);
            assertThat(round.roundingLoss()).isEqualTo(Fraction.of(59, 100));
        }
    }

    private static Candidate candidateNamed(List<Candidate> candidates, String name) {
        return candidates.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No candidate named " + name));
    }

    private static Stream<String> readLines(String resourcePath) {
        InputStream in = Ers97ModelElectionFixtureTest.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("Missing test resource: " + resourcePath);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines();
    }
}
