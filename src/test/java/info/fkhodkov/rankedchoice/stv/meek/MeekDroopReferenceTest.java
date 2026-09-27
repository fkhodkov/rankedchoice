package info.fkhodkov.rankedchoice.stv.meek;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;

/**
 * Replays elections against reports produced by droop itself ({@code jklundell/droop} v0.14,
 * commit cf58293, {@code python3 Droop.py meek arithmetic=fixed defeat_batch=none <file>.blt}),
 * checked in next to the ballots under {@code testdata/meek/droop/}. Each of our rounds is paired
 * with droop's Elect or Defeat action at the same position (droop's closing "Defeat remaining"
 * entries aren't rounds here), and must agree to the last of droop's 9 decimal places:
 * <ul>
 * <li>the action, and for an exclusion the candidate (candidates elected together may be listed
 * in a different order, so elected candidates are compared as a set over the whole count);
 * <li>every tally we report;
 * <li>the residual (our exhausted votes);
 * <li>for a quota election, the surplus: droop's vote for that candidate minus droop's quota.
 * </ul>
 * The cases: MapLibre's real 2025 election (see {@link MeekMapLibreGoverningBoard2025FixtureTest});
 * a synthetic count whose exclusions need droop's tie-break (every hopeful within the remaining
 * surplus of the lowest counts as tied); and a synthetic count where keep-factor iteration stops
 * because the surplus stopped shrinking rather than dropping below omega.
 */
class MeekDroopReferenceTest {

    private static final Path DIR = Path.of("src/test/resources/testdata/meek");
    private static final Pattern STANDING =
            Pattern.compile("^\\t(?:Elected|Hopeful|Defeated|Pending):\\s+(.+) \\((-?[\\d.]+)\\)$");

    private record DroopAction(boolean elected, String name, Map<String, Fraction> votes, Fraction quota,
            Fraction residual) {
    }

    @ParameterizedTest
    @CsvSource({
            "maplibre-governing-board-2025.blt, droop/maplibre-governing-board-2025.droop.txt",
            "droop/synthetic-tiebreak.blt, droop/synthetic-tiebreak.droop.txt",
            "droop/synthetic-stable.blt, droop/synthetic-stable.droop.txt"})
    void matchesDroopRoundByRound(String ballots, String report) throws IOException {
        ParsedElection election = BltParser.parse(DIR.resolve(ballots));
        MeekRuleset ruleset = new MeekRuleset(Exact.INSTANCE, new DeclaredOrderTieBreak(election.candidates()));
        StvResult result = MeekCounter.count(election.candidates(), election.ballots(), election.seats(), ruleset);
        List<DroopAction> droop = parse(Files.readString(DIR.resolve(report)));

        assertThat(result.rounds()).hasSameSizeAs(droop);
        for (int i = 0; i < droop.size(); i++) {
            RoundRecord ours = result.rounds().get(i);
            DroopAction theirs = droop.get(i);
            String round = "round " + ours.roundNumber();
            assertThat(ours.action() instanceof RoundAction.Elected).as(round).isEqualTo(theirs.elected());
            if (ours.action() instanceof RoundAction.Eliminated(var candidate)) {
                assertThat(candidate.name()).as(round).isEqualTo(theirs.name());
            }
            for (Map.Entry<Candidate, Fraction> tally : ours.tallies().entrySet()) {
                assertThat(tally.getValue()).as(round + " " + tally.getKey().name())
                        .isEqualTo(theirs.votes().get(tally.getKey().name()));
            }
            assertThat(ours.exhaustedVotes()).as(round + " residual").isEqualTo(theirs.residual());
            if (ours.action() instanceof RoundAction.Elected(var candidate, var surplus) && surplus.signum() > 0) {
                assertThat(surplus).as(round + " surplus")
                        .isEqualTo(theirs.votes().get(candidate.name()).subtract(theirs.quota()));
            }
        }
        Set<String> electedByDroop = new LinkedHashSet<>();
        droop.stream().filter(DroopAction::elected).forEach(action -> electedByDroop.add(action.name()));
        assertThat(result.electedInOrder()).extracting(Candidate::name).containsExactlyInAnyOrderElementsOf(electedByDroop);
    }

    private static List<DroopAction> parse(String report) {
        List<DroopAction> actions = new ArrayList<>();
        String[] parts = report.split("\nAction: ");
        for (int p = 1; p < parts.length; p++) {
            String part = parts[p];
            String head = part.lines().findFirst().orElseThrow();
            if (head.startsWith("Defeat remaining") || !(head.startsWith("Elect") || head.startsWith("Defeat"))) {
                continue;
            }
            Map<String, Fraction> votes = new HashMap<>();
            for (String line : part.lines().toList()) {
                Matcher matcher = STANDING.matcher(line);
                if (matcher.matches()) {
                    // droop lists candidates on equal votes together: "C1, C2 (0.000000000)"
                    for (String name : matcher.group(1).split(", ")) {
                        votes.put(name, decimal(matcher.group(2)));
                    }
                }
            }
            actions.add(new DroopAction(head.startsWith("Elect"), head.substring(head.indexOf(": ") + 2).strip(),
                    votes, decimal(field(part, "Quota")), decimal(field(part, "Residual"))));
        }
        return actions;
    }

    private static String field(String part, String label) {
        Matcher matcher = Pattern.compile("\\t" + label + ": (-?[\\d.]+)").matcher(part);
        if (!matcher.find()) {
            throw new AssertionError("droop action has no " + label + ": " + part);
        }
        return matcher.group(1);
    }

    private static Fraction decimal(String text) {
        BigDecimal value = new BigDecimal(text);
        return Fraction.of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()));
    }
}
