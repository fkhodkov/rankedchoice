package info.fkhodkov.rankedchoice.stv.fixtures;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;

/**
 * An ERS97 count sheet: one block per stage ("stage N"), every candidate's votes ("name:
 * value"), and the non-transferable votes, exhausted and lost to rounding together. Read from
 * the rulebook's model election or from pyRCV2's count of an election.
 */
record CountSheet(List<Stage> stages) {

    record Stage(int number, Map<String, Fraction> votes, Fraction nonTransferable) {
    }

    static CountSheet read(Path path) throws IOException {
        List<Stage> stages = new ArrayList<>();
        int number = 0;
        Map<String, Fraction> votes = null;
        Fraction nonTransferable = null;
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("stage ")) {
                if (votes != null) {
                    stages.add(new Stage(number, votes, nonTransferable));
                }
                number = Integer.parseInt(line.substring("stage ".length()));
                votes = new LinkedHashMap<>();
                continue;
            }
            int colon = line.indexOf(": ");
            String name = line.substring(0, colon);
            String value = line.substring(colon + 2);
            switch (name) {
                case "comment", "votes required" -> {
                }
                case "non-transferable" -> nonTransferable = decimal(value);
                default -> votes.put(name, decimal(value));
            }
        }
        stages.add(new Stage(number, votes, nonTransferable));
        return new CountSheet(stages);
    }

    /**
     * The stage each round matches, in order: the first stage, no earlier than the last
     * round's, with every candidate's votes in the round's tallies and its exhausted plus lost
     * votes. Null for a round that matches none.
     */
    List<Integer> match(StvResult result) {
        List<Integer> matched = new ArrayList<>();
        int from = 0;
        for (RoundRecord round : result.rounds()) {
            Integer match = null;
            for (int i = from; i < stages.size() && match == null; i++) {
                if (matches(round, stages.get(i))) {
                    match = i;
                }
            }
            matched.add(match == null ? null : stages.get(match).number());
            if (match != null) {
                from = match;
            }
        }
        return matched;
    }

    private static boolean matches(RoundRecord round, Stage stage) {
        for (Map.Entry<Candidate, Fraction> tally : round.tallies().entrySet()) {
            if (!tally.getValue().equals(stage.votes().get(tally.getKey().name()))) {
                return false;
            }
        }
        return round.exhaustedVotes().add(round.roundingLoss()).equals(stage.nonTransferable());
    }

    /** A decimal, or an exact fraction "n/d" (pyRCV2's exact counts print them). */
    private static Fraction decimal(String text) {
        int slash = text.indexOf('/');
        if (slash > 0) {
            return Fraction.of(new BigInteger(text.substring(0, slash)), new BigInteger(text.substring(slash + 1)));
        }
        BigDecimal value = new BigDecimal(text);
        return value.scale() <= 0 ? Fraction.of(value.toBigIntegerExact())
                : Fraction.of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()));
    }
}
