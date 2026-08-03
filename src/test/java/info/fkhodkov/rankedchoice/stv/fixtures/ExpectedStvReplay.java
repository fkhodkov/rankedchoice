package info.fkhodkov.rankedchoice.stv.fixtures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * A hand-transcribed, round-by-round expected result loaded from a plain-text resource file,
 * so real-election test fixtures can compare the engine's actual output against official
 * figures without hardcoding those figures into Java assertions. See
 * {@code testdata/scottish/*.expected.txt} for the file format and worked examples.
 */
public record ExpectedStvReplay(Fraction quota, List<ExpectedRound> rounds) {

    public record ExpectedRound(
            int roundNumber, Map<String, Fraction> tallies, ExpectedAction action, Fraction exhausted) {
    }

    /**
     * What the resource file expects to have happened in a round. {@code surplus} is empty
     * when the file doesn't assert a surplus value for an "elected" round — used for rounds
     * where the engine's "remaining continuing == remaining seats" shortcut reports a surplus
     * of {@link Fraction#ZERO} instead of the true mathematical surplus.
     */
    public sealed interface ExpectedAction {
        String candidateName();

        record Elected(String candidateName, Optional<Fraction> surplus) implements ExpectedAction {
        }

        record Eliminated(String candidateName) implements ExpectedAction {
        }
    }

    public static ExpectedStvReplay parse(Stream<String> lines) {
        List<String> content = lines.map(String::stripTrailing)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))
                .toList();

        Fraction quota = null;
        List<ExpectedRound> rounds = new ArrayList<>();
        int roundNumber = 0;
        Map<String, Fraction> tallies = new LinkedHashMap<>();
        ExpectedAction action = null;

        for (String line : content) {
            if (line.startsWith("quota:")) {
                quota = parseFraction(line.substring("quota:".length()).trim());
            } else if (line.startsWith("round ")) {
                roundNumber = Integer.parseInt(line.substring("round ".length()).trim());
                tallies = new LinkedHashMap<>();
                action = null;
            } else if (line.startsWith("action:")) {
                action = parseAction(line.substring("action:".length()).trim());
            } else if (line.startsWith("exhausted:")) {
                Fraction exhausted = parseFraction(line.substring("exhausted:".length()).trim());
                rounds.add(new ExpectedRound(roundNumber, tallies, action, exhausted));
            } else {
                int colon = line.lastIndexOf(':');
                String name = line.substring(0, colon).trim();
                Fraction value = parseFraction(line.substring(colon + 1).trim());
                tallies.put(name, value);
            }
        }

        if (quota == null) {
            throw new IllegalStateException("Expected-results resource is missing a 'quota:' line");
        }
        return new ExpectedStvReplay(quota, rounds);
    }

    private static ExpectedAction parseAction(String text) {
        String[] parts = text.split("\\s+");
        String type = parts[0];
        String candidateName;
        if (type.equals("elected")) {
            int surplusIndex = indexOf(parts, "surplus");
            if (surplusIndex < 0) {
                candidateName = String.join(" ", Arrays.asList(parts).subList(1, parts.length));
                return new ExpectedAction.Elected(candidateName, Optional.empty());
            }
            candidateName = String.join(" ", Arrays.asList(parts).subList(1, surplusIndex));
            Fraction surplus = parseFraction(parts[surplusIndex + 1]);
            return new ExpectedAction.Elected(candidateName, Optional.of(surplus));
        } else if (type.equals("eliminated")) {
            candidateName = String.join(" ", Arrays.asList(parts).subList(1, parts.length));
            return new ExpectedAction.Eliminated(candidateName);
        }
        throw new IllegalArgumentException("Unknown action type: " + type);
    }

    private static int indexOf(String[] parts, String value) {
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(value)) {
                return i;
            }
        }
        return -1;
    }

    private static Fraction parseFraction(String text) {
        int slash = text.indexOf('/');
        if (slash < 0) {
            return Fraction.of(Long.parseLong(text));
        }
        long numerator = Long.parseLong(text.substring(0, slash));
        long denominator = Long.parseLong(text.substring(slash + 1));
        return Fraction.of(numerator, denominator);
    }
}
