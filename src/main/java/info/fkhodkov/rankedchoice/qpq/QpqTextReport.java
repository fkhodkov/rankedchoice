package info.fkhodkov.rankedchoice.qpq;

import java.util.Map;
import java.util.stream.Collectors;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * Renders a {@link QpqResult} as a human-readable, stage-by-stage plain-text report. Unlike
 * {@link info.fkhodkov.rankedchoice.stv.report.StvTextReport}, there's no fixed rounding
 * precision to align figures to (see {@link QpqRuleset}), so every quotient and quota is
 * simply shown in exact decimal.
 */
public final class QpqTextReport {

    private QpqTextReport() {
    }

    public static String render(QpqResult result) {
        StringBuilder text = new StringBuilder();
        if (result.informal().total().signum() > 0) {
            text.append("Informal ballots: ").append(result.informal().describe()).append("\n\n");
        }

        for (QpqRoundRecord round : result.rounds()) {
            text.append("Stage ").append(round.stageNumber());
            if (round.restarted()) {
                text.append(" (restarted)");
            }
            text.append('\n');
            text.append("  Quota: ").append(round.quota().toDecimalString()).append('\n');
            for (Map.Entry<Candidate, Fraction> entry : sortedByQuotientDescending(round)) {
                text.append("  ")
                        .append(entry.getKey().name())
                        .append(": ")
                        .append(entry.getValue().toDecimalString())
                        .append('\n');
            }
            text.append("  -> ").append(describe(round.action())).append('\n').append('\n');
        }

        text.append("Elected: ").append(result.electedInOrder().stream()
                .map(Candidate::name)
                .collect(Collectors.joining(", ")));
        text.append('\n');
        return text.toString();
    }

    private static Iterable<Map.Entry<Candidate, Fraction>> sortedByQuotientDescending(QpqRoundRecord round) {
        return round.quotients().entrySet().stream()
                .sorted(Map.Entry.<Candidate, Fraction>comparingByValue().reversed())
                .toList();
    }

    private static String describe(QpqAction action) {
        return switch (action) {
            case QpqAction.Elected(var candidate) -> candidate.name() + " elected";
            case QpqAction.Excluded(var candidate) -> candidate.name() + " excluded";
        };
    }
}
