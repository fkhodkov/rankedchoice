package info.fkhodkov.rankedchoice.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.CsvBallotParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedBallots;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.config.ConfigLoader;
import info.fkhodkov.rankedchoice.core.config.ElectionConfig;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.config.StvRulesetParser;
import info.fkhodkov.rankedchoice.stv.meek.MeekCounter;
import info.fkhodkov.rankedchoice.stv.meek.MeekRuleset;
import info.fkhodkov.rankedchoice.stv.report.StvCsvReport;
import info.fkhodkov.rankedchoice.stv.report.StvHtmlReport;
import info.fkhodkov.rankedchoice.stv.report.StvJsonReport;
import info.fkhodkov.rankedchoice.stv.report.StvTextReport;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Tabulates an STV election from a ballot file. Rules can come from a bundled preset
 * ({@code --ruleset}), a YAML config file ({@code --config}), individual override flags
 * ({@code --quota}, {@code --transfer}, {@code --rounding}, {@code --tiebreak}), or the
 * defaults (Droop quota, fractional transfer, exact rounding, declared-order tie-break) —
 * in that order of precedence, flag over config over default.
 */
@Command(name = "rankedchoice", mixinStandardHelpOptions = true,
        description = "Tabulate a single transferable vote election from a ballot file.")
public final class Main implements Callable<Integer> {

    private static final ElectionConfig EMPTY_CONFIG = new ElectionConfig(null, null, null, null, null, null, null);

    /** One bundled preset shipped as a YAML resource under {@code src/main/resources/rulesets/}. */
    private record BundledRuleset(String name, String description) {
    }

    /** Every bundled preset, in the order shown by {@code --help} and unknown-ruleset errors. */
    private static final List<BundledRuleset> BUNDLED_RULESETS = List.of(
            new BundledRuleset("scottish",
                    "Scottish local government elections -- Droop quota, WIGM, truncate to 5dp"),
            new BundledRuleset("ers97",
                    "Electoral Reform Society 1997 rules, many UK organisations -- Droop rounded to 2dp, "
                            + "last-parcel, truncate to 2dp"),
            new BundledRuleset("ers76",
                    "Predecessor to ERS97 -- same settings as ers97; no verified-accurate spec of its own"),
            new BundledRuleset("hare-clark-qpq",
                    "Tasmanian Hare-Clark \"quota preferential\" -- Droop, last-parcel, truncate to 2dp"),
            new BundledRuleset("act-hare-clark",
                    "ACT Legislative Assembly's Hare-Clark -- Droop, last-parcel with deferred, "
                            + "per-candidate rounding, truncate to 6dp"));

    @Option(names = {"-i", "--input"}, required = true, description = "Ballot file (.blt or .csv)")
    private Path input;

    @Option(names = {"-m", "--method"}, description = "Counting method: stv|meek. Defaults to stv. "
            + "--quota/--transfer don't apply to meek (its quota and transfer are fixed by the method itself).")
    private String method;

    @Option(names = {"-f", "--format"}, description = "Input format: blt or csv. Inferred from the file extension if omitted.")
    private String format;

    @Option(names = {"-s", "--seats"},
            description = "Seats to fill. Required for CSV input; for BLT input, overrides the file's own seat count if given.")
    private Integer seats;

    @Option(names = {"-o", "--output"}, description = "Output file. Defaults to standard output.")
    private Path output;

    @Option(names = {"--report-format"},
            description = "Report output format: text|csv|json|html. Defaults to text; inferred from "
                    + "--output's extension if that's given and this isn't.")
    private String reportFormat;

    @Option(names = {"-c", "--config"}, description = "YAML config file with quota/transfer/rounding/tiebreak/seats/format.")
    private Path config;

    @Option(names = {"-r", "--ruleset"}, description = "A bundled ruleset preset: scottish|ers97|ers76|"
            + "hare-clark-qpq|act-hare-clark. Mutually exclusive with --config.")
    private String rulesetName;

    @Option(names = {"--quota"}, description = "droop|hare|hagenbach-bischoff|imperiali. Overrides --config/--ruleset.")
    private String quota;

    @Option(names = {"--transfer"}, description = "fractional|wigm. Overrides --config/--ruleset.")
    private String transfer;

    @Option(names = {"--rounding"}, description = "exact|truncate:N. Overrides --config/--ruleset.")
    private String rounding;

    @Option(names = {"--tiebreak"},
            description = "Comma-separated fallback chain: declared|previous-round|first-preference|"
                    + "seeded-random:SEED, e.g. 'previous-round,declared'. Overrides --config/--ruleset.")
    private String tiebreak;

    static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() {
        try {
            String report = runCount();
            if (output != null) {
                Files.writeString(output, report);
            } else {
                System.out.print(report);
            }
            return 0;
        } catch (IOException e) {
            System.err.println("Error: failed to read or write a file: " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private String runCount() {
        ElectionConfig fileConfig = loadFileConfig();

        String resolvedFormat = firstNonNull(format, fileConfig.format());
        String inputFormat = resolvedFormat != null ? resolvedFormat.toLowerCase(Locale.ROOT) : inferFormat(input);

        List<Candidate> candidates;
        List<Ballot> ballots;
        int effectiveSeats;

        switch (inputFormat) {
            case "blt" -> {
                ParsedElection election = BltParser.parse(input);
                candidates = election.candidates().stream()
                        .filter(candidate -> !election.withdrawnCandidates().contains(candidate))
                        .toList();
                ballots = election.ballots();
                effectiveSeats = resolveSeats(fileConfig, election.seats());
            }
            case "csv" -> {
                ParsedBallots parsed = CsvBallotParser.parse(input);
                candidates = parsed.candidates();
                ballots = parsed.ballots();
                Integer configuredSeats = resolveSeatsOrNull(fileConfig);
                if (configuredSeats == null) {
                    throw new IllegalArgumentException("--seats is required for CSV input");
                }
                effectiveSeats = configuredSeats;
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported format '" + inputFormat + "'; expected 'blt' or 'csv'");
        }

        String effectiveMethod = firstNonNull(method, "stv").toLowerCase(Locale.ROOT);
        StvResult result;
        RoundingRule roundingRuleForReport;
        switch (effectiveMethod) {
            case "stv" -> {
                StvRuleset ruleset = StvRulesetParser.parse(
                        firstNonNull(quota, fileConfig.quota()),
                        firstNonNull(transfer, fileConfig.transfer()),
                        firstNonNull(rounding, fileConfig.rounding()),
                        firstNonNull(tiebreak, fileConfig.tiebreak()),
                        candidates);
                result = StvCounter.count(candidates, ballots, effectiveSeats, ruleset);
                roundingRuleForReport = ruleset.roundingRule();
            }
            case "meek" -> {
                if (quota != null || transfer != null) {
                    throw new IllegalArgumentException(
                            "--quota/--transfer don't apply to --method meek; its quota and transfer are fixed by the method itself");
                }
                roundingRuleForReport = StvRulesetParser.parseRounding(firstNonNull(rounding, fileConfig.rounding()));
                MeekRuleset ruleset = new MeekRuleset(roundingRuleForReport,
                        StvRulesetParser.parseTieBreak(firstNonNull(tiebreak, fileConfig.tiebreak()), candidates));
                result = MeekCounter.count(candidates, ballots, effectiveSeats, ruleset);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported method '" + effectiveMethod + "'; expected 'stv' or 'meek'");
        }

        String resolvedReportFormat = firstNonNull(reportFormat, fileConfig.reportFormat());
        String effectiveReportFormat = resolvedReportFormat != null
                ? resolvedReportFormat.toLowerCase(Locale.ROOT)
                : inferReportFormat(output);
        return switch (effectiveReportFormat) {
            case "text" -> StvTextReport.render(result, roundingRuleForReport);
            case "csv" -> StvCsvReport.render(result, roundingRuleForReport);
            case "json" -> StvJsonReport.render(result, roundingRuleForReport);
            case "html" -> StvHtmlReport.render(result, roundingRuleForReport);
            default -> throw new IllegalArgumentException(
                    "Unsupported report format '" + effectiveReportFormat + "'; expected 'text', 'csv', 'json', or 'html'");
        };
    }

    /** Falls back to {@code text} when neither {@code --report-format} nor a recognized {@code --output} extension is given. */
    private static String inferReportFormat(Path output) {
        if (output == null) {
            return "text";
        }
        String name = output.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) {
            return "csv";
        }
        if (name.endsWith(".json")) {
            return "json";
        }
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return "html";
        }
        return "text";
    }

    private ElectionConfig loadFileConfig() {
        if (config != null && rulesetName != null) {
            throw new IllegalArgumentException("--config and --ruleset are mutually exclusive; pass only one");
        }
        if (config != null) {
            return ConfigLoader.load(config);
        }
        if (rulesetName != null) {
            boolean known = BUNDLED_RULESETS.stream().anyMatch(preset -> preset.name().equals(rulesetName));
            if (!known) {
                throw new IllegalArgumentException(unknownRulesetMessage(rulesetName));
            }
            return ConfigLoader.loadResource("/rulesets/" + rulesetName + ".yaml");
        }
        return EMPTY_CONFIG;
    }

    private static String unknownRulesetMessage(String rulesetName) {
        int nameColumnWidth = BUNDLED_RULESETS.stream().mapToInt(preset -> preset.name().length()).max().orElse(0);
        StringBuilder message = new StringBuilder("Unknown ruleset '").append(rulesetName).append("'.\n")
                .append("Available rulesets:\n");
        for (BundledRuleset preset : BUNDLED_RULESETS) {
            message.append("  ")
                    .append(preset.name())
                    .append(" ".repeat(nameColumnWidth - preset.name().length() + 2))
                    .append(preset.description())
                    .append('\n');
        }
        return message.toString();
    }

    private int resolveSeats(ElectionConfig fileConfig, int fallback) {
        Integer configured = resolveSeatsOrNull(fileConfig);
        return configured != null ? configured : fallback;
    }

    private Integer resolveSeatsOrNull(ElectionConfig fileConfig) {
        return seats != null ? seats : fileConfig.seats();
    }

    private static String firstNonNull(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static String inferFormat(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".blt")) {
            return "blt";
        }
        if (name.endsWith(".csv")) {
            return "csv";
        }
        throw new IllegalArgumentException(
                "Cannot infer format from file name '" + path.getFileName() + "'; pass --format explicitly");
    }
}
