package info.fkhodkov.rankedchoice.count;

/**
 * The options of a count, each null where it isn't given. They're named, in errors, by the command
 * line's flags ({@code --quota} and so on). A given option overrides the config or ruleset's.
 *
 * @param method       stv (the default), meek, warren, cambridge, schulze, borda, cpo-stv, schulze-stv,
 *                     schulze-proportional or qpq
 * @param ruleset      a bundled ruleset's name ({@link Rulesets}); not with a config of one's own
 * @param seats        the seats to fill, over the config's and the ballot file's
 * @param quota        as {@code --quota}
 * @param transfer     as {@code --transfer}
 * @param rounding     as {@code --rounding}
 * @param tiebreak     as {@code --tiebreak}
 * @param tiedRankings as {@code --tied-rankings}
 * @param minimumPreferences as {@code --minimum-preferences}
 * @param precision    as {@code --precision} (meek and warren)
 * @param bordaScoring classic or dowdall (borda)
 */
public record CountOptions(
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
        String bordaScoring
) {

    /** No options: an stv count by the defaults. */
    public static final CountOptions NONE =
            new CountOptions(null, null, null, null, null, null, null, null, null, null, null);
}
