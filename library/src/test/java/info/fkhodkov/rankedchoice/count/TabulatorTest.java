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
        return new CountOptions(method, null, null, null, null, null, null, null, null, null, null, null);
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
        CountOptions options = new CountOptions(null, "scottish", 1, "hare", null, null, null, null, null, null, null,
                null);
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
        CountOptions cambridge = new CountOptions("cambridge", null, 1, null, null, null, null, null, null, null, null,
                null);
        assertThat(Tabulator.of(cambridge, null).count(election, warning -> { }).report(ReportFormat.JSON))
                .contains("Andrea");
    }

    @Test
    void refusesTwoCandidatesOfTheSameName() {
        // Counted, the JSON report kept only the second Alex's 3 votes.
        Election election = Election.parse("3 1\n5 1 0\n3 2 0\n1 3 0\n0\n\"Alex\"\n\"Alex\"\n\"Sam\"\n\"Test\"\n",
                "blt");

        assertThatThrownBy(() -> Tabulator.of(method("stv"), null).count(election, warning -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("candidates 1 and 2 are both named \"Alex\"");
    }

    @Test
    void refusesACandidateListedTwice() {
        // Borda counted A twice: 20 points to B's 10, where once over it's B, 3 to 2.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Election election = new Election(List.of(a, a, b), List.of(
                new Ballot(List.of(Set.of(a)), BigInteger.TWO),
                new Ballot(List.of(Set.of(b)), BigInteger.valueOf(3))), null);

        assertThatThrownBy(() -> Tabulator.of(method("borda"), null).count(election, warning -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidate A (\"A\") is listed twice");
    }

    @Test
    void refusesAnOptionTheMethodWouldIgnore() {
        CountOptions seats = new CountOptions("schulze", null, 2, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> Tabulator.of(seats, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--seats doesn't apply to --method schulze: it ranks every candidate rather than filling "
                        + "seats");
    }

    @Test
    void aGivenListTakesTheFirstPlacesOfTheProportionalRanking() {
        Election election = Election.read(WIKIPEDIA, null);
        CountOptions options = new CountOptions("schulze-proportional", null, null, null, null, null, null, null,
                null, null, null, List.of("Delilah", "Carter"));

        Tabulation tabulation = Tabulator.of(options, null).count(election, warning -> { });

        assertThat(tabulation.report(ReportFormat.JSON)).contains("\"ranking\" : [ \"Delilah\", \"Carter\"");
        assertThat(tabulation.report(ReportFormat.TEXT))
                .contains("1. Delilah  (given; potential winners: Andrea)");
    }

    @Test
    void aGivenListMustNameCandidatesOnceAndOnlyForTheProportionalRanking() {
        Election election = Election.read(WIKIPEDIA, null);
        CountOptions stv = new CountOptions(null, null, null, null, null, null, null, null, null, null, null,
                List.of("Andrea"));
        assertThatThrownBy(() -> Tabulator.of(stv, null))
                .hasMessage("--given-list doesn't apply to --method stv: only schulze-proportional uses it");
        for (List<String> list : List.of(List.of("Andrea", "Zed"), List.of("Brad", "Brad"))) {
            CountOptions options = new CountOptions("schulze-proportional", null, null, null, null, null, null, null,
                    null, null, null, list);
            assertThatThrownBy(() -> Tabulator.of(options, null).count(election, warning -> { }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(list.get(1).equals("Zed") ? "--given-list names Zed, who isn't a candidate"
                            : "--given-list names Brad twice");
        }
    }

    @Test
    void anUnknownRulesetListsTheBundledOnes() {
        CountOptions options = new CountOptions(null, "nope", null, null, null, null, null, null, null, null, null,
                null);

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
