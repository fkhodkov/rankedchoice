package info.fkhodkov.rankedchoice.stv.meek;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;

/**
 * Replays a real Meek STV election — MapLibre's 2025 Governing Board election, run on OpaVote
 * (6 candidates, 5 seats, 92 ballots, 89 valid) — from its real ballot data (see the citation
 * in {@code testdata/meek/maplibre-governing-board-2025.blt}) and checks the result against
 * OpaVote's own published results PDF, mirrored alongside the ballots in the same GitHub
 * directory.
 *
 * <p>The winner set, the round-by-round election structure (two simultaneous elections, then
 * two more, then a final one — no exclusion is ever needed), and every exact whole-number
 * round-1 tally match the PDF exactly. Where this project's engine and OpaVote's diverge is
 * more interesting than either alone: an earlier version of {@link MeekCounter} fully converged
 * the currently-elected candidates' keep factors to tight tolerance before ever checking
 * whether a hopeful had crossed quota, which gave the same eventual winners but kept converging
 * Yuri Astrakhan and Birk Skyum for many more iterations than OpaVote's process did before
 * noticing Stephanie May and Haowen You had already qualified — visibly wrong intermediate
 * figures, confirmed by comparing this project's own iteration-by-iteration trace directly
 * against the PDF's published per-round keep factors (its "Round 2" states "Keep factors of
 * candidates who have exceeded the threshold: Yuri Astrakhan, 0.436275 and Birk Skyum,
 * 0.593334" — exactly this project's first weight-update iteration; its "Round 3" states
 * "Yuri Astrakhan, 0.358391; Stephanie May, 0.908879; Birk Skyum, 0.404223; and Haowen You,
 * 0.845134" — exactly this project's second, at which point Stephanie and Haowen were already
 * elected in the real count but the old code hadn't even checked for them yet). Checking rule
 * 2.6 (has any hopeful reached quota?) on every iteration rather than gating it behind rule
 * 2.9's full convergence — as droop's Meek rule, which {@link MeekCounter} now follows exactly,
 * also does — fixed this: that engine's own final keep factors (Yuri Astrakhan 0.288123, Birk
 * Skyum 0.326209, Haowen You 0.618475, Stephanie May 0.642674, read off {@code round5}'s surplus/tallies below) now agree with the
 * PDF's final published figures (0.288123, 0.326210, 0.618476, 0.642675 respectively) to
 * 5-6 significant digits. The figures asserted below are unchanged under the current
 * fixed-point engine, and match droop's report for this election to all 9 decimal places (see
 * {@link MeekDroopReferenceTest}).
 *
 * <p>This project doesn't expose keep factors directly (only each round's tallies and, for an
 * {@code Elected} action, the surplus over quota at the moment of election), so the assertions
 * below check the exact figures this project's {@link StvResult} does surface, which are
 * derived from — and were cross-checked against — those same keep factors.
 */
class MeekMapLibreGoverningBoard2025FixtureTest {

    @Test
    void reproducesThe2025MapLibreGoverningBoardResult() {
        ParsedElection election = BltParser.parse(
                Path.of("src/test/resources/testdata/meek/maplibre-governing-board-2025.blt"));
        List<Candidate> candidates = election.candidates();
        MeekRuleset ruleset = new MeekRuleset(new TruncateDecimal(6), new DeclaredOrderTieBreak(candidates));

        StvResult result = MeekCounter.count(candidates, election.ballots(), election.seats(), ruleset);

        Candidate yuri = candidateNamed(candidates, "Yuri Astrakhan");
        Candidate frank = candidateNamed(candidates, "Frank Elsinga");
        Candidate stefan = candidateNamed(candidates, "Stefan Karschti");
        Candidate stephanie = candidateNamed(candidates, "Stephanie May");
        Candidate birk = candidateNamed(candidates, "Birk Skyum");
        Candidate haowen = candidateNamed(candidates, "Haowen You");

        assertThat(result.electedInOrder()).containsExactly(yuri, birk, haowen, stephanie, frank);
        assertThat(result.electedInOrder()).doesNotContain(stefan);
        assertThat(result.rounds()).hasSize(5);
        assertThat(result.rounds().stream().noneMatch(round -> round.action() instanceof RoundAction.Eliminated))
                .as("the real count never needed to exclude anyone -- Karschti simply never caught up")
                .isTrue();

        // Round 1: raw first preferences (89 valid ballots, quota 89/6). Yuri and Birk both
        // clear quota already on first preferences alone and are elected together, matching
        // the PDF's own round 1 exactly (34/3/5/11/25/11, dashed quota line at ~14.83).
        RoundRecord round1 = result.rounds().get(0);
        assertThat(round1.tallies()).isEqualTo(Map.of(
                yuri, Fraction.of(34), frank, Fraction.of(3), stefan, Fraction.of(5),
                stephanie, Fraction.of(11), birk, Fraction.of(25), haowen, Fraction.of(11)));
        assertThat(round1.action()).isEqualTo(new RoundAction.Elected(yuri, Fraction.of(9583333, 500000)));

        RoundRecord round2 = result.rounds().get(1);
        assertThat(round2.tallies()).isEqualTo(round1.tallies());
        assertThat(round2.action()).isEqualTo(new RoundAction.Elected(birk, Fraction.of(5083333, 500000)));

        // Round 3/4 (this project's numbering): one weight-update iteration for Yuri/Birk
        // (0.436275 / 0.593334, matching the PDF's own "Round 2" exactly) already pushes
        // Stephanie and Haowen over quota, so both are elected together here too -- the PDF's
        // "Round 2" text says exactly this: "Candidates Stephanie May and Haowen You have
        // reached the threshold and are elected."
        RoundRecord round3 = result.rounds().get(2);
        assertThat(round3.tallies()).isEqualTo(Map.of(
                frank, Fraction.of(7776633, 1_000_000), stefan, Fraction.of(3672941, 500_000),
                stephanie, Fraction.of(16133039, 1_000_000), haowen, Fraction.of(17349901, 1_000_000)));
        assertThat(round3.action()).isEqualTo(new RoundAction.Elected(haowen, Fraction.of(1343469, 500_000)));

        RoundRecord round4 = result.rounds().get(3);
        assertThat(round4.tallies()).isEqualTo(round3.tallies());
        assertThat(round4.action()).isEqualTo(new RoundAction.Elected(stephanie, Fraction.of(367519, 250_000)));

        // Round 5 (final): Frank crosses quota purely through continued keep-factor
        // convergence of all four already-elected candidates -- their keep factors at this
        // point (0.288123 / 0.326209 / 0.618475 / 0.642674) agree with the PDF's final "Round
        // 9" figures (0.288123 / 0.326210 / 0.618476 / 0.642675) to 5-6 significant digits.
        RoundRecord round5 = result.rounds().get(4);
        assertThat(round5.tallies()).isEqualTo(Map.of(
                frank, Fraction.of(14445221, 1_000_000), stefan, Fraction.of(14325181, 1_000_000)));
        assertThat(round5.action()).isEqualTo(new RoundAction.Elected(frank, Fraction.of(1157, 62_500)));
        assertThat(result.quota()).isEqualTo(Fraction.of(14426709, 1_000_000));
    }

    private static Candidate candidateNamed(List<Candidate> candidates, String name) {
        return candidates.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No candidate named " + name));
    }
}
