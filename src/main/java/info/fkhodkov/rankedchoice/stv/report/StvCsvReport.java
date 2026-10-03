package info.fkhodkov.rankedchoice.stv.report;

import java.util.Map;
import java.util.OptionalInt;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.report.ReportMarkup;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * Renders an {@link StvResult} as CSV, one row per candidate per round — the same figures as
 * {@link StvTextReport}, reshaped for spreadsheets. Each round's {@link RoundAction} is
 * recorded only on its own candidate's row ({@code action} is blank for every other candidate
 * that round); {@code exhausted_votes} repeats the round's running total on every row of that
 * round, since CSV has no notion of a merged cell, as does {@code rounding_loss} (the votes lost
 * to rounding so far, see {@link RoundRecord#roundingLoss()}), and {@code informal_ballots} likewise repeats
 * the number of ballots set aside before the count on every row (the breakdown by reason is
 * in the other formats).
 */
public final class StvCsvReport {

    private StvCsvReport() {
    }

    public static String render(StvResult result, RoundingRule roundingRule) {
        OptionalInt decimalPlaces = ReportFormatting.decimalPlacesOf(roundingRule);
        StringBuilder csv = new StringBuilder("round,candidate,tally,action,exhausted_votes,rounding_loss,informal_ballots\n");
        for (RoundRecord round : result.rounds()) {
            String exhausted = ReportFormatting.format(round.exhaustedVotes(), decimalPlaces);
            String loss = ReportFormatting.format(round.roundingLoss(), decimalPlaces);
            String informal = result.informal().total().toString();
            for (Map.Entry<Candidate, Fraction> entry : ReportFormatting.sortedByTallyDescending(round)) {
                csv.append(round.roundNumber()).append(',')
                        .append(field(entry.getKey().name())).append(',')
                        .append(ReportFormatting.format(entry.getValue(), decimalPlaces)).append(',')
                        .append(field(actionFor(round.action(), entry.getKey(), decimalPlaces))).append(',')
                        .append(exhausted).append(',')
                        .append(loss).append(',')
                        .append(informal).append('\n');
            }
        }
        return csv.toString();
    }

    private static String actionFor(RoundAction action, Candidate candidate, OptionalInt decimalPlaces) {
        if (!action.candidate().equals(candidate)) {
            return "";
        }
        return switch (action) {
            case RoundAction.Elected(_, var surplus) when surplus.signum() > 0 ->
                    "elected (surplus " + ReportFormatting.format(surplus, decimalPlaces) + ")";
            case RoundAction.Elected _ -> "elected";
            case RoundAction.Eliminated _ -> "eliminated";
        };
    }

    private static String field(String value) {
        return ReportMarkup.csvField(value);
    }
}
