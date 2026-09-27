package info.fkhodkov.rankedchoice.core.config;

/**
 * The overridable pieces of an election run that can come from a YAML config file or a
 * bundled ruleset preset: every field is optional (null means "not specified here"), and
 * callers decide their own precedence when combining this with CLI flags and defaults.
 */
public record ElectionConfig(
        String quota,
        String transfer,
        String rounding,
        String tiebreak,
        Integer seats,
        String format,
        String reportFormat,
        Boolean batchExclusion,
        String tiedRankings,
        Integer minimumPreferences,
        Integer precision
) {
}
