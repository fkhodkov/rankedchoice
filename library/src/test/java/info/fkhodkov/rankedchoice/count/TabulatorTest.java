package info.fkhodkov.rankedchoice.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/** The counting API the command line (and anything else) counts through. */
class TabulatorTest {

    private static final Path WIKIPEDIA =
            Path.of("src/test/resources/testdata/wikipedia/wikipedia-andrea-brad-carter-delilah.blt");

    private static CountOptions method(String method) {
        return new CountOptions(method, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void countsAFileByTheDefaultsAndRendersEveryFormat() {
        Election election = Election.read(WIKIPEDIA, null);
        assertThat(election.seats()).isEqualTo(2);

        Tabulation tabulation = Tabulator.of(CountOptions.NONE, null).count(election, warning -> { });

        assertThat(tabulation.report(ReportFormat.TEXT)).contains("Quota: 20").contains("Elected: Andrea, Brad");
        assertThat(tabulation.report(ReportFormat.JSON)).contains("\"elected\"");
        assertThat(tabulation.report(ReportFormat.CSV)).startsWith("round,candidate,tally");
        assertThat(tabulation.report(ReportFormat.HTML)).startsWith("<!DOCTYPE html>");
    }

    @Test
    void optionsOverrideTheRulesetTheyName() {
        CountOptions options = new CountOptions(null, "scottish", 1, "hare", null, null, null, null, null, null, null);
        Tabulator tabulator = Tabulator.of(options, null);

        assertThat(tabulator.config().transfer()).isEqualTo("wigm");
        assertThat(tabulator.count(Election.read(WIKIPEDIA, "blt"), warning -> { }).report(ReportFormat.TEXT))
                .contains("Quota: 57.00000");
    }

    @Test
    void everyMethodCountsTheSameElection() {
        Election election = Election.read(WIKIPEDIA, null);
        for (String method : List.of("stv", "meek", "warren", "schulze", "borda", "cpo-stv", "schulze-stv",
                "schulze-proportional", "qpq")) {
            String json = Tabulator.of(method(method), null).count(election, warning -> { }).report(ReportFormat.JSON);
            assertThat(json).as(method).contains("Andrea");
        }
        // Cambridge STV needs 50 ballots a seat, and the 57 here fill one.
        CountOptions cambridge = new CountOptions("cambridge", null, 1, null, null, null, null, null, null, null, null);
        assertThat(Tabulator.of(cambridge, null).count(election, warning -> { }).report(ReportFormat.JSON))
                .contains("Andrea");
    }

    @Test
    void refusesAnOptionTheMethodWouldIgnore() {
        CountOptions seats = new CountOptions("schulze", null, 2, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> Tabulator.of(seats, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--seats doesn't apply to --method schulze: it ranks every candidate rather than filling "
                        + "seats");
    }

    @Test
    void anUnknownRulesetListsTheBundledOnes() {
        CountOptions options = new CountOptions(null, "nope", null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> Tabulator.of(options, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Unknown ruleset 'nope'.\nAvailable rulesets:\n  scottish ");
        assertThat(Rulesets.ALL).extracting(Rulesets.Ruleset::name).contains("scottish", "ers97", "irv");
    }

    @Test
    void anElectionWithNoSeatsOfItsOwnNeedsThemOnlyToFillSeats() {
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        List<Ballot> ballots = new ArrayList<>();
        ballots.add(new Ballot(List.of(Set.of(a), Set.of(b)), BigInteger.TWO));
        ballots.add(new Ballot(List.of(Set.of(b)), BigInteger.ONE));
        Election election = new Election(List.of(a, b), ballots, null);

        assertThatThrownBy(() -> Tabulator.of(CountOptions.NONE, null).count(election, warning -> { }))
                .hasMessage("--seats is required for CSV input");
        assertThat(Tabulator.of(method("borda"), null).count(election, warning -> { }).report(ReportFormat.TEXT))
                .contains("A");
    }

    @Test
    void readsBallotsFromTextTakingTheFormatFromTheTextWhenNotGiven() throws Exception {
        String blt = Files.readString(WIKIPEDIA);
        String csv = "\uFEFFCount,rank1,rank2\n3,A,B\n2,B,\n";

        assertThat(Election.parse(blt, null)).isEqualTo(Election.read(WIKIPEDIA, null));
        Election fromCsv = Election.parse(csv, null);
        assertThat(fromCsv.candidates()).extracting(Candidate::name).containsExactly("A", "B");
        assertThat(fromCsv.seats()).isNull();
        assertThatThrownBy(() -> Election.parse(blt, "xml"))
                .hasMessage("Unsupported format 'xml'; expected 'blt' or 'csv'");
    }

    @Test
    void listsEveryMethodWithTheOptionsItRefuses() {
        assertThat(Tabulator.METHODS).extracting(Tabulator.Method::name).containsExactly("stv", "meek", "warren",
                "cambridge", "qpq", "cpo-stv", "schulze-stv", "schulze-proportional", "schulze", "borda");
        assertThat(Tabulator.unusedOptions("Meek")).containsKeys("--quota", "--transfer");
        assertThat(Tabulator.unusedOptions("stv")).doesNotContainKey("--quota");
        assertThat(Tabulator.unusedOptions("nope")).isEmpty();
    }

    @Test
    void anUnknownMethodIsRefusedWhenCounted() {
        Tabulator tabulator = Tabulator.of(method("Foo"), null);

        assertThat(tabulator.method()).isEqualTo("foo");
        assertThatThrownBy(() -> tabulator.count(Election.read(WIKIPEDIA, null), warning -> { }))
                .hasMessageStartingWith("Unsupported method 'foo'");
    }
}
