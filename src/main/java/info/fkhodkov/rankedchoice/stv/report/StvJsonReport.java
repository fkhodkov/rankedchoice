package info.fkhodkov.rankedchoice.stv.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.InformalBallots;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * Renders an {@link StvResult} as a JSON document with the same figures as {@link
 * StvTextReport} — every {@link Fraction} formatted through the same {@code roundingRule}
 * rather than exposed as a raw numerator/denominator pair — so this doubles as a
 * machine-readable golden-fixture format for other tools to check a count against.
 */
public final class StvJsonReport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private StvJsonReport() {
    }

    public record Action(String type, String candidate, String surplusTransferred) {
    }

    public record Round(int roundNumber, Map<String, String> tallies, Action action, String exhaustedVotes) {
    }

    public record Informal(BigInteger total, BigInteger blank, BigInteger tiedRankings, BigInteger tooFewPreferences) {
    }

    public record Report(String quota, Informal informalBallots, List<Round> rounds, List<String> electedInOrder) {
    }

    public static String render(StvResult result, RoundingRule roundingRule) {
        try {
            return MAPPER.writeValueAsString(toReport(result, roundingRule));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize STV result to JSON", e);
        }
    }

    /** The structured tree {@link #render} serializes, exposed directly for callers that want the objects. */
    public static Report toReport(StvResult result, RoundingRule roundingRule) {
        OptionalInt decimalPlaces = ReportFormatting.decimalPlacesOf(roundingRule);
        List<Round> rounds = result.rounds().stream().map(round -> toRound(round, decimalPlaces)).toList();
        List<String> electedInOrder = result.electedInOrder().stream().map(Candidate::name).toList();
        InformalBallots informal = result.informal();
        Informal informalBallots = new Informal(
                informal.total(), informal.blank(), informal.tiedRankings(), informal.tooFewPreferences());
        return new Report(ReportFormatting.format(result.quota(), decimalPlaces), informalBallots, rounds, electedInOrder);
    }

    private static Round toRound(RoundRecord round, OptionalInt decimalPlaces) {
        Map<String, String> tallies = new LinkedHashMap<>();
        for (Map.Entry<Candidate, Fraction> entry : ReportFormatting.sortedByTallyDescending(round)) {
            tallies.put(entry.getKey().name(), ReportFormatting.format(entry.getValue(), decimalPlaces));
        }
        Action action = switch (round.action()) {
            case RoundAction.Elected(var candidate, var surplus) ->
                    new Action("elected", candidate.name(), ReportFormatting.format(surplus, decimalPlaces));
            case RoundAction.Eliminated(var candidate) -> new Action("eliminated", candidate.name(), null);
        };
        return new Round(round.roundNumber(), tallies, action,
                ReportFormatting.format(round.exhaustedVotes(), decimalPlaces));
    }
}
