package info.fkhodkov.rankedchoice.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/** The counting API the command line (and anything else) counts through. */
class TabulatorTest {

    private static final Path WIKIPEDIA =
            Path.of("src/test/resources/testdata/wikipedia/wikipedia-andrea-brad-carter-delilah.blt");

    private static CountOptions method(String method) {
        return CountOptions.NONE.withMethod(method);
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
        CountOptions options = CountOptions.NONE.withRuleset("scottish").withSeats(1).withQuota("hare");
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
        CountOptions cambridge = CountOptions.NONE.withMethod("cambridge").withSeats(1);
        assertThat(Tabulator.of(cambridge, null).count(election, warning -> { }).report(ReportFormat.JSON))
                .contains("Andrea");
    }

    @Test
    void everyMethodGivesUpOnAnInterruptedThread() {
        Election election = Election.read(WIKIPEDIA, null);
        List<CountOptions> counts = new ArrayList<>();
        for (String method : List.of("stv", "meek", "warren", "schulze", "borda", "cpo-stv", "schulze-stv",
                "schulze-proportional", "qpq")) {
            counts.add(method(method));
        }
        counts.add(CountOptions.NONE.withMethod("cambridge").withSeats(1));
        List<String> counted = new ArrayList<>();
        for (CountOptions options : counts) {
            Tabulator tabulator = Tabulator.of(options, null);
            Thread.currentThread().interrupt();
            try {
                tabulator.count(election, warning -> { });
                counted.add(options.method());
            } catch (CancellationException e) {
                assertThat(e).as(options.method()).hasMessage("the count was stopped");
                assertThat(Thread.currentThread().isInterrupted()).as(options.method()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
        assertThat(counted).as("counted to the end despite the interrupt").isEmpty();
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
        CountOptions seats = CountOptions.NONE.withMethod("schulze").withSeats(2);

        assertThatThrownBy(() -> Tabulator.of(seats, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--seats doesn't apply to --method schulze: it ranks every candidate rather than filling "
                        + "seats");
    }

    @Test
    void everyMethodGivesItsResultAsData() {
        Election election = Election.read(WIKIPEDIA, null);
        List<String> outcomes = new ArrayList<>();
        for (String method : List.of("stv", "meek", "warren", "cambridge", "qpq", "cpo-stv", "schulze-stv",
                "schulze-proportional", "schulze", "borda")) {
            CountOptions options = method(method).withSeats(method.equals("cambridge") ? 1 : null);
            Tabulation tabulation = Tabulator.of(options, null).count(election, warning -> { });
            String outcome = switch (tabulation) {
                case Tabulation.Stv stv -> "elected " + names(stv.result().electedInOrder());
                case Tabulation.Qpq qpq -> "elected " + names(qpq.result().electedInOrder());
                case Tabulation.CpoStv cpo -> "committees " + cpo.result().winners().stream()
                        .map(TabulatorTest::sortedNames).toList();
                case Tabulation.SchulzeStv sstv -> "committees " + sstv.result().winners().stream()
                        .map(TabulatorTest::sortedNames).toList();
                case Tabulation.SchulzeProportional list -> "ranked " + names(list.result().ranking());
                case Tabulation.Schulze schulze -> "ranked " + schulze.result().ranking().stream()
                        .map(TabulatorTest::sortedNames).toList();
                case Tabulation.Borda borda -> "ranked " + names(borda.result().rankingBestToWorst());
            };
            outcomes.add(method + ": " + outcome);
        }

        assertThat(outcomes).containsExactly(
                "stv: elected [Andrea, Brad]",
                "meek: elected [Andrea, Brad]",
                "warren: elected [Andrea, Brad]",
                "cambridge: elected [Andrea]",
                "qpq: elected [Andrea, Brad]",
                "cpo-stv: committees [[Andrea, Brad]]",
                "schulze-stv: committees [[Andrea, Brad]]",
                "schulze-proportional: ranked [Andrea, Brad, Delilah, Carter]",
                "schulze: ranked [[Andrea], [Brad], [Delilah], [Carter]]",
                "borda: ranked [Andrea, Brad, Delilah, Carter]");
    }

    @Test
    void everyResultRefusesToBeChanged() {
        Election election = Election.read(WIKIPEDIA, null);
        for (String method : List.of("stv", "qpq", "cpo-stv", "schulze-stv", "schulze-proportional", "schulze",
                "borda")) {
            Tabulation tabulation = Tabulator.of(method(method), null).count(election, warning -> { });
            String before = tabulation.report(ReportFormat.JSON);
            List<Runnable> changes = switch (tabulation) {
                case Tabulation.Stv stv -> List.of(() -> stv.result().electedInOrder().clear());
                case Tabulation.Qpq qpq -> List.of(() -> qpq.result().electedInOrder().clear());
                case Tabulation.CpoStv cpo -> List.of(() -> cpo.result().ranking().clear(),
                        () -> cpo.result().ranking().getFirst().clear(),
                        () -> cpo.result().outcomeScores().values().iterator().next().clear());
                case Tabulation.SchulzeStv sstv -> List.of(() -> sstv.result().supports().clear(),
                        () -> sstv.result().winners().getFirst().clear(),
                        () -> sstv.result().supports().getFirst().committee().clear());
                case Tabulation.SchulzeProportional list -> List.of(() -> list.result().ranking().clear(),
                        () -> list.result().places().getFirst().comparisons().values().iterator().next().clear(),
                        () -> list.result().places().getFirst().potentialWinners().clear());
                case Tabulation.Schulze schulze -> List.of(() -> schulze.result().ranking().getFirst().clear(),
                        () -> schulze.result().pairwiseWins().values().iterator().next().clear(),
                        () -> schulze.result().strongestPaths().clear());
                case Tabulation.Borda borda -> List.of(() -> borda.result().scores().clear(),
                        () -> borda.result().rankingBestToWorst().clear());
            };
            for (Runnable change : changes) {
                assertThatThrownBy(change::run).as(method).isInstanceOf(UnsupportedOperationException.class);
            }
            assertThat(tabulation.report(ReportFormat.JSON)).as(method).isEqualTo(before);
        }
    }

    private static List<String> names(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::name).toList();
    }

    private static List<String> sortedNames(Set<Candidate> candidates) {
        return candidates.stream().map(Candidate::name).sorted().toList();
    }

    @Test
    void aGivenListTakesTheFirstPlacesOfTheProportionalRanking() {
        Election election = Election.read(WIKIPEDIA, null);
        CountOptions options = CountOptions.NONE.withMethod("schulze-proportional")
                .withGivenList(List.of("Delilah", "Carter"));

        Tabulation tabulation = Tabulator.of(options, null).count(election, warning -> { });

        assertThat(tabulation.report(ReportFormat.JSON)).contains("\"ranking\" : [ \"Delilah\", \"Carter\"");
        assertThat(tabulation.report(ReportFormat.TEXT))
                .contains("1. Delilah  (given; potential winners: Andrea)");
    }

    @Test
    void aGivenListMustNameCandidatesOnceAndOnlyForTheProportionalRanking() {
        Election election = Election.read(WIKIPEDIA, null);
        CountOptions stv = CountOptions.NONE.withGivenList(List.of("Andrea"));
        assertThatThrownBy(() -> Tabulator.of(stv, null))
                .hasMessage("--given-list doesn't apply to --method stv: only schulze-proportional uses it");
        for (List<String> list : List.of(List.of("Andrea", "Zed"), List.of("Brad", "Brad"))) {
            CountOptions options = CountOptions.NONE.withMethod("schulze-proportional").withGivenList(list);
            assertThatThrownBy(() -> Tabulator.of(options, null).count(election, warning -> { }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(list.get(1).equals("Zed") ? "--given-list names Zed, who isn't a candidate"
                            : "--given-list names Brad twice");
        }
    }

    @Test
    void anUnknownRulesetListsTheBundledOnes() {
        CountOptions options = CountOptions.NONE.withRuleset("nope");

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
