package info.fkhodkov.rankedchoice.stv.report;

import java.util.Map;
import java.util.stream.Collectors;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;

/** Renders an {@link StvResult} as a human-readable, round-by-round plain-text report. */
public final class StvTextReport {

    private StvTextReport() {
    }

    public static String render(StvResult result) {
        StringBuilder text = new StringBuilder();
        text.append("Quota: ").append(result.quota()).append("\n\n");

        for (RoundRecord round : result.rounds()) {
            text.append("Round ").append(round.roundNumber()).append('\n');
            for (Map.Entry<Candidate, Fraction> entry : sortedByTallyDescending(round)) {
                text.append("  ").append(entry.getKey().name()).append(": ").append(entry.getValue()).append('\n');
            }
            text.append("  -> ").append(describe(round.action())).append('\n');
            if (round.exhaustedVotes().signum() > 0) {
                text.append("  (exhausted so far: ").append(round.exhaustedVotes()).append(")\n");
            }
            text.append('\n');
        }

        text.append("Elected: ").append(result.electedInOrder().stream()
                .map(Candidate::name)
                .collect(Collectors.joining(", ")));
        text.append('\n');
        return text.toString();
    }

    private static Iterable<Map.Entry<Candidate, Fraction>> sortedByTallyDescending(RoundRecord round) {
        return round.tallies().entrySet().stream()
                .sorted(Map.Entry.<Candidate, Fraction>comparingByValue().reversed())
                .toList();
    }

    private static String describe(RoundAction action) {
        return switch (action) {
            case RoundAction.Elected elected when elected.surplusTransferred().signum() > 0 ->
                    elected.candidate().name() + " elected (surplus " + elected.surplusTransferred() + " transferred)";
            case RoundAction.Elected elected -> elected.candidate().name() + " elected";
            case RoundAction.Eliminated eliminated -> eliminated.candidate().name() + " eliminated";
        };
    }
}
