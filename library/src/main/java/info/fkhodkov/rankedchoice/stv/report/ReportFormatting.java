package info.fkhodkov.rankedchoice.stv.report;

import java.util.Map;
import java.util.OptionalInt;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;

/**
 * Figure formatting and candidate ordering shared by every {@code Stv*Report} exporter, so a
 * given {@link info.fkhodkov.rankedchoice.stv.StvResult} renders the same numbers (and the
 * same tally-descending candidate order) regardless of which output format was chosen.
 */
final class ReportFormatting {

    private ReportFormatting() {
    }

    /** {@code truncate:N}'s fixed decimal places to render every figure with, or empty for {@link Exact}. */
    static OptionalInt decimalPlacesOf(RoundingRule roundingRule) {
        return switch (roundingRule) {
            case TruncateDecimal(var decimalPlaces) -> OptionalInt.of(decimalPlaces);
            case Exact _ -> OptionalInt.empty();
        };
    }

    static String format(Fraction value, OptionalInt decimalPlaces) {
        return decimalPlaces.isPresent() ? value.toDecimalString(decimalPlaces.getAsInt()) : value.toDecimalString();
    }

    static Iterable<Map.Entry<Candidate, Fraction>> sortedByTallyDescending(RoundRecord round) {
        return round.tallies().entrySet().stream()
                .sorted(Map.Entry.<Candidate, Fraction>comparingByValue().reversed())
                .toList();
    }
}
