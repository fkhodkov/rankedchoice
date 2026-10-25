package info.fkhodkov.rankedchoice.cambridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;

/**
 * Replays a synthetic election (1,000 voters, 21 candidates; 5 surplus transfers, a ten-candidate
 * batch exclusion, tie-breaks) against OpenSTV's own CambridgeSTV count of it, stored as its CSV
 * report (see {@code testdata/cambridge/README.md}). Every action must match OpenSTV's stages in
 * order -- each surplus transfer, each exclusion batch -- and before each one, every candidate's
 * count and the non-transferable total must equal OpenSTV's at that stage; the winners too. (The
 * same check passes on 1,000 random elections, tools/reference/openstv/cambridge.py; this one is kept as a
 * regression test.)
 */
class CambridgeOpenStvReferenceTest {

    private static final Path DIR = Path.of("src/test/resources/testdata/cambridge");

    @Test
    void matchesOpenStvStageByStage() throws IOException {
        ParsedElection election = BltParser.parse(DIR.resolve("synthetic.blt"));
        StvResult result = CambridgeCounter.count(election.candidates(), election.ballots(), election.seats(),
                new CambridgeRuleset(new DeclaredOrderTieBreak(election.candidates())));

        List<List<String>> rows = Files.readAllLines(DIR.resolve("synthetic.openstv.csv")).stream()
                .map(CambridgeOpenStvReferenceTest::cells).toList();
        int header = 0;
        while (rows.get(header).isEmpty() || !rows.get(header).getFirst().equals("Candidates")) {
            header++;
        }
        List<String> kinds = rows.get(header - 1);
        List<String> names = rows.get(header);
        int stages = (names.size() - 2) / 2;
        List<Map<String, Integer>> counts = new ArrayList<>();
        for (int s = 0; s <= stages; s++) {
            counts.add(new HashMap<>());
        }
        int[] nonTransferable = new int[stages + 1];
        Set<String> elected = new HashSet<>();
        for (List<String> row : rows.subList(header + 1, rows.size())) {
            if (row.size() < 2 || row.getFirst().equals("Totals")) {
                continue;
            }
            for (int s = 0; s <= stages; s++) {
                String value = s == 0 ? row.get(1) : row.get(1 + 2 * s);
                if (row.getFirst().equals("Non-transferable")) {
                    nonTransferable[s] = value.isBlank() ? 0 : Integer.parseInt(value);
                } else if (!value.equals("-")) {
                    counts.get(s).put(row.getFirst(), Integer.parseInt(value));
                }
            }
            if (row.contains("Elected")) {
                elected.add(row.getFirst());
            }
        }

        List<List<RoundRecord>> steps = new ArrayList<>();
        for (RoundRecord round : result.rounds()) {
            boolean surplus = round.action() instanceof RoundAction.Elected(var _, var s) && s.signum() > 0;
            boolean exclusion = round.action() instanceof RoundAction.Eliminated;
            if (exclusion && !steps.isEmpty() && steps.getLast().getFirst().action() instanceof RoundAction.Eliminated
                    && steps.getLast().getFirst().tallies().equals(round.tallies())) {
                steps.getLast().add(round); // same decision: one batch
            } else if (surplus || exclusion) {
                steps.add(new ArrayList<>(List.of(round)));
            }
        }

        assertThat(steps).hasSize(stages);
        for (int k = 0; k < stages; k++) {
            List<RoundRecord> step = steps.get(k);
            String stage = "stage " + (k + 2);
            List<String> ours = step.stream().map(r -> r.action().candidate().name()).toList();
            assertThat(kinds.get(2 + 2 * k)).as(stage).startsWith(
                    step.getFirst().action() instanceof RoundAction.Elected ? "Surplus of" : "Exclusion of");
            assertThat(ours).as(stage).containsExactlyInAnyOrder(names.get(2 + 2 * k).split("\\+"));
            for (Map.Entry<Candidate, Fraction> tally : step.getFirst().tallies().entrySet()) {
                assertThat(tally.getValue()).as(stage + " " + tally.getKey().name())
                        .isEqualTo(Fraction.of(counts.get(k).get(tally.getKey().name())));
            }
            assertThat(step.getFirst().exhaustedVotes()).as(stage + " non-transferable")
                    .isEqualTo(Fraction.of(nonTransferable[k]));
        }
        assertThat(result.electedInOrder()).extracting(Candidate::name).containsExactlyInAnyOrderElementsOf(elected);
    }

    /** The cells of a CSV line, unquoted (OpenSTV's report never puts a comma inside quotes). */
    private static List<String> cells(String line) {
        return Arrays.stream(line.split(",", -1)).map(cell -> cell.replace("\"", "").trim()).toList();
    }
}
