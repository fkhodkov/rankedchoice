package info.fkhodkov.rankedchoice.server;

/**
 * A count asked of {@code POST /api/count}, as JSON: the ballots as text, and the options by the
 * names the command line gives its flags, in camel case ({@code --tied-rankings} is
 * {@code tiedRankings}). Everything but {@code ballots} may be left out.
 *
 * @param ballots      the ballot file's text, BLT or CSV
 * @param format       blt or csv; taken from the text when left out
 * @param reportFormat text (the default), csv, json or html
 */
public record CountRequest(
        String ballots,
        String format,
        String method,
        String ruleset,
        Integer seats,
        String quota,
        String transfer,
        String rounding,
        String tiebreak,
        String tiedRankings,
        Integer minimumPreferences,
        Integer precision,
        String bordaScoring,
        String reportFormat
) {
}
