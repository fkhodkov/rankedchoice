package info.fkhodkov.rankedchoice.count;

import java.util.List;
import info.fkhodkov.rankedchoice.core.config.ConfigLoader;
import info.fkhodkov.rankedchoice.core.config.ElectionConfig;

/** The bundled rulesets: presets shipped as YAML resources under {@code rulesets/}. */
public final class Rulesets {

    /** One bundled preset, and what it's for. */
    public record Ruleset(String name, String description) {
    }

    /** Every bundled preset, in the order help and unknown-ruleset errors list them. */
    public static final List<Ruleset> ALL = List.of(
            new Ruleset("scottish",
                    "Scottish local government elections -- Droop quota, WIGM, truncate to 5dp"),
            new Ruleset("ers97",
                    "Electoral Reform Society 1997 rules, many UK organisations -- Droop rounded to 2dp, "
                            + "last-parcel, truncate to 2dp"),
            new Ruleset("ers76",
                    "Predecessor to ERS97 -- same settings as ers97; no verified-accurate spec of its own"),
            new Ruleset("hare-clark-qpq",
                    "Tasmanian Hare-Clark \"quota preferential\" -- Droop, last parcel over every paper, "
                            + "transfer value truncated to 6dp, whole votes, ballots need 7 preferences"),
            new Ruleset("act-hare-clark",
                    "ACT Legislative Assembly's Hare-Clark -- Droop, last-parcel with deferred, "
                            + "per-candidate rounding, truncate to 6dp"),
            new Ruleset("senate",
                    "Australian Senate -- Droop, unweighted inclusive Gregory, whole votes, surpluses "
                            + "in order of election, last candidates elected as they stand"),
            new Ruleset("nsw-lg",
                    "NSW local government councillor elections -- Droop, weighted inclusive Gregory over "
                            + "transferable papers, whole votes per parcel, surpluses in order of election"),
            new Ruleset("vic-lc",
                    "Victorian Legislative Council -- Droop, unweighted inclusive Gregory, whole votes, "
                            + "exclusions first preferences first, last candidates elected as they stand"),
            new Ruleset("wa-lc",
                    "Western Australian Legislative Council -- Droop, weighted inclusive Gregory, whole votes "
                            + "per parcel, exclusions parcel by parcel, last candidates elected as they stand"),
            new Ruleset("portland",
                    "Portland, Oregon City Council -- Droop, fractional transfer truncated twice to 4dp, "
                            + "surpluses transferred together, overvotes skipped"),
            new Ruleset("minneapolis",
                    "Minneapolis multi-seat contests -- Droop, fractional transfer truncated twice to 4dp, "
                            + "hopeless candidates defeated before each surplus, overvotes skipped"),
            new Ruleset("irv",
                    "Instant-runoff for one seat -- the fewest votes defeated round by round until one candidate "
                            + "is left, as Alameda County counts"),
            new Ruleset("albany",
                    "Albany, California -- Droop, fractional transfer truncated twice to 4dp, surpluses largest "
                            + "first, as Alameda County's Dominion count"));

    private Rulesets() {
    }

    /** The bundled ruleset of this name; an unknown name's error lists them all. */
    public static ElectionConfig load(String name) {
        if (ALL.stream().noneMatch(preset -> preset.name().equals(name))) {
            throw new IllegalArgumentException(unknownMessage(name));
        }
        return ConfigLoader.loadResource("/rulesets/" + name + ".yaml");
    }

    private static String unknownMessage(String name) {
        int nameColumnWidth = ALL.stream().mapToInt(preset -> preset.name().length()).max().orElse(0);
        StringBuilder message = new StringBuilder("Unknown ruleset '").append(name).append("'.\n")
                .append("Available rulesets:\n");
        for (Ruleset preset : ALL) {
            message.append("  ")
                    .append(preset.name())
                    .append(" ".repeat(nameColumnWidth - preset.name().length() + 2))
                    .append(preset.description())
                    .append('\n');
        }
        return message.toString();
    }
}
