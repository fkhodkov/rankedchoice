package info.fkhodkov.rankedchoice.stv.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.config.ConfigLoader;
import info.fkhodkov.rankedchoice.core.config.ElectionConfig;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.config.StvRulesetParser;

/**
 * All five electorates of the 2020 ACT Legislative Assembly election, from Elections ACT's own
 * ballot paper preference data, against its official "Final Distribution of Preferences" (see
 * {@code testdata/act/README.md}). Every round of this count matches one of the official counts,
 * in order: every candidate's votes, the votes exhausted and the loss by fraction, exactly, to
 * the 6 decimal places the Act specifies. And the same five candidates are elected.
 *
 * <p>The official count has more counts than this one has rounds: it records each value group of
 * an exclusion as a count of its own, where a round here covers the whole exclusion.
 */
class ActOfficialDistributionTest {

    private static final Path DIRECTORY = Path.of("src/test/resources/testdata/act");
    private static final Pattern ELECTED = Pattern.compile("((?:[A-Z][a-z'-]+ )+)([A-Z'-]{2,}(?: [A-Z'-]{2,})*) elected \\d");

    private record Count(int number, Map<String, Fraction> votes, Fraction exhausted, Fraction loss, String remarks) {
    }

    @ParameterizedTest
    @ValueSource(strings = {"brindabella", "ginninderra", "kurrajong", "murrumbidgee", "yerrabi"})
    void everyRoundMatchesAnOfficialCount(String electorate) throws IOException {
        ParsedElection election = BltParser.parse(DIRECTORY.resolve("act-2020-" + electorate + ".blt"));
        List<Candidate> candidates = election.candidates();
        ElectionConfig act = ConfigLoader.loadResource("/rulesets/act-hare-clark.yaml");
        StvRuleset ruleset = StvRulesetParser.parse(act.quota(), act.transfer(), act.rounding(), act.tiebreak(),
                candidates);
        StvResult result = StvCounter.count(candidates, election.ballots(), election.seats(), ruleset);
        List<Count> official = read(DIRECTORY.resolve("act-2020-" + electorate + ".official.txt"));

        int from = 0;
        for (RoundRecord round : result.rounds()) {
            Integer match = null;
            for (int i = from; i < official.size() && match == null; i++) {
                if (matches(round, official.get(i))) {
                    match = i;
                }
            }
            assertThat(match).as("%s round %d matches an official count", electorate, round.roundNumber()).isNotNull();
            assertThat(round.roundingLoss()).as("%s round %d: loss by fraction (official count %d)", electorate,
                    round.roundNumber(), official.get(match).number()).isEqualTo(official.get(match).loss());
            from = match;
        }

        Set<String> electedOfficially = new java.util.HashSet<>();
        for (Count count : official) {
            Matcher matcher = ELECTED.matcher(count.remarks());
            while (matcher.find()) {
                electedOfficially.add(key(matcher.group(1) + matcher.group(2)));
            }
        }
        assertThat(result.electedInOrder().stream().map(candidate -> key(candidate.name())).collect(Collectors.toSet()))
                .hasSize(5)
                .isEqualTo(electedOfficially);
    }

    /** Every candidate in the round's tallies has the official votes, and so do the exhausted votes. */
    private static boolean matches(RoundRecord round, Count count) {
        for (Map.Entry<Candidate, Fraction> tally : round.tallies().entrySet()) {
            if (!tally.getValue().equals(count.votes().get(tally.getKey().name()))) {
                return false;
            }
        }
        return round.exhaustedVotes().equals(count.exhausted());
    }

    private static List<Count> read(Path path) throws IOException {
        List<Count> counts = new ArrayList<>();
        int number = 0;
        Map<String, Fraction> votes = null;
        Fraction exhausted = null;
        Fraction loss = null;
        String remarks = "";
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("count ")) {
                if (votes != null) {
                    counts.add(new Count(number, votes, exhausted, loss, remarks));
                }
                number = Integer.parseInt(line.substring("count ".length()));
                votes = new LinkedHashMap<>();
                remarks = "";
                continue;
            }
            int colon = line.indexOf(": ");
            String name = line.substring(0, colon);
            String value = line.substring(colon + 2);
            switch (name) {
                case "exhausted" -> exhausted = decimal(value);
                case "loss" -> loss = decimal(value);
                case "remarks" -> remarks = value;
                default -> votes.put(name, decimal(value));
            }
        }
        counts.add(new Count(number, votes, exhausted, loss, remarks));
        return counts;
    }

    private static Fraction decimal(String text) {
        BigDecimal value = new BigDecimal(text);
        return value.scale() <= 0 ? Fraction.of(value.toBigIntegerExact())
                : Fraction.of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }
}
