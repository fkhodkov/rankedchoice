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
import info.fkhodkov.rankedchoice.borda.BordaMethod;
import info.fkhodkov.rankedchoice.borda.BordaResult;
import info.fkhodkov.rankedchoice.borda.BordaScoringRule;
import info.fkhodkov.rankedchoice.borda.BordaTextReport;
import info.fkhodkov.rankedchoice.borda.ClassicBorda;
import info.fkhodkov.rankedchoice.borda.DowdallScoring;
import info.fkhodkov.rankedchoice.cpostv.CpoStvCounter;
import info.fkhodkov.rankedchoice.cpostv.CpoStvResult;
import info.fkhodkov.rankedchoice.cpostv.CpoStvRuleset;
import info.fkhodkov.rankedchoice.cpostv.CpoStvTextReport;
import info.fkhodkov.rankedchoice.qpq.QpqCounter;
import info.fkhodkov.rankedchoice.qpq.QpqResult;
import info.fkhodkov.rankedchoice.qpq.QpqRuleset;
import info.fkhodkov.rankedchoice.qpq.QpqTextReport;
import info.fkhodkov.rankedchoice.schulze.SchulzeMethod;
import info.fkhodkov.rankedchoice.schulze.SchulzeResult;
import info.fkhodkov.rankedchoice.schulze.SchulzeTextReport;
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
 * Tabulates a ranked-choice election from a ballot file, by STV, Meek's method, CPO-STV, QPQ,
 * Schulze, or Borda ({@code --method}). For STV/Meek, rules can come from a bundled preset
 * ({@code --ruleset}), a YAML config file ({@code --config}), individual override flags
 * ({@code --quota}, {@code --transfer}, {@code --rounding}, {@code --tiebreak}), or the
 * defaults (Droop quota, fractional transfer, exact rounding, declared-order tie-break) — in
 * that order of precedence, flag over config over default. CPO-STV reuses the same quota/
 * transfer/rounding flags without a bundled preset or tie-break; QPQ (Woodall's Quota
 * Preferential by Quotient) only takes a tie-break, since its quota and transfer are both
 * derived formulas rather than configurable. Schulze and Borda don't use any of those.
 */
@Command(name = "rankedchoice", mixinStandardHelpOptions = true,
        description = "Tabulate a ranked-choice election from a ballot file.")
public final class Main implements Callable<Integer> {

    private static final ElectionConfig EMPTY_CONFIG =
            new ElectionConfig(null, null, null, null, null, null, null, null, null, null);

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
                    "Tasmanian Hare-Clark \"quota preferential\" -- Droop, last-parcel, truncate to 2dp, "
                            + "ballots need 7 preferences"),
            new BundledRuleset("act-hare-clark",
                    "ACT Legislative Assembly's Hare-Clark -- Droop, last-parcel with deferred, "
                            + "per-candidate rounding, truncate to 6dp"));

    @Option(names = {"-i", "--input"}, required = true, description = "Ballot file (.blt or .csv)")
    private Path input;

    @Option(names = {"-m", "--method"}, description = "Counting method: stv|meek|schulze|borda|cpo-stv|qpq. Defaults "
            + "to stv. --quota/--transfer don't apply to meek (its quota and transfer are fixed by the method "
            + "itself); schulze/borda don't use --seats, --quota, --transfer, --rounding, or --tiebreak at all; "
            + "cpo-stv uses --seats/--quota/--transfer/--rounding but not --tiebreak; qpq (Woodall's Quota "
            + "Preferential by Quotient -- unrelated to the hare-clark-qpq *ruleset*, a different, classic-STV "
            + "preset despite the similar name) uses --seats/--tiebreak but not --quota/--transfer/--rounding, "
            + "since its quota and transfer are both derived formulas, not configurable.")
    private String method;

    @Option(names = {"--borda-scoring"}, description = "classic|dowdall. Only applies to --method borda; "
            + "defaults to classic. 'dowdall' is the Nauru system (1, 1/2, 1/3, ...).")
    private String bordaScoring;

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

    @Option(names = {"--tied-rankings"},
            description = "What to do with a ballot ranking several candidates equally: reject|split|truncate|skip. "
                    + "split divides the ballot between them (fractional transfer only); truncate stops the ballot "
                    + "at the tie (a tie at first preference voids it, as Scottish law requires); skip drops the "
                    + "tied rank and continues. Defaults to split under fractional transfer, reject otherwise. "
                    + "Only applies to --method stv. Overrides --config/--ruleset.")
    private String tiedRankings;

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
        String effectiveMethod = firstNonNull(method, "stv").toLowerCase(Locale.ROOT);
        boolean seatsBasedMethod = effectiveMethod.equals("stv") || effectiveMethod.equals("meek")
                || effectiveMethod.equals("cpo-stv") || effectiveMethod.equals("qpq");
        if (tiedRankings != null && !effectiveMethod.equals("stv")) {
            throw new IllegalArgumentException("--tied-rankings only applies to --method stv");
        }

        String resolvedFormat = firstNonNull(format, fileConfig.format());
        String inputFormat = resolvedFormat != null ? resolvedFormat.toLowerCase(Locale.ROOT) : inferFormat(input);

        List<Candidate> candidates;
        List<Ballot> ballots;
        Integer effectiveSeats;

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
                effectiveSeats = resolveSeatsOrNull(fileConfig);
                if (seatsBasedMethod && effectiveSeats == null) {
                    throw new IllegalArgumentException("--seats is required for CSV input");
                }
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported format '" + inputFormat + "'; expected 'blt' or 'csv'");
        }

        if (effectiveMethod.equals("schulze") || effectiveMethod.equals("borda")) {
            return runNonStvMethod(effectiveMethod, candidates, ballots);
        }
        if (effectiveMethod.equals("cpo-stv")) {
            CpoStvRuleset ruleset = new CpoStvRuleset(
                    StvRulesetParser.parseQuota(firstNonNull(quota, fileConfig.quota())),
                    StvRulesetParser.parseTransfer(firstNonNull(transfer, fileConfig.transfer())),
                    StvRulesetParser.parseRounding(firstNonNull(rounding, fileConfig.rounding())));
            CpoStvResult result = CpoStvCounter.count(candidates, ballots, effectiveSeats, ruleset);
            return CpoStvTextReport.render(result);
        }
        if (effectiveMethod.equals("qpq")) {
            if (quota != null || transfer != null || rounding != null) {
                throw new IllegalArgumentException(
                        "--quota/--transfer/--rounding don't apply to --method qpq; its quota is a derived "
                                + "formula recalculated every stage, its transfer is fixed by the method itself, "
                                + "and every figure is this project's exact Fraction with no rounding needed");
            }
            QpqRuleset ruleset = new QpqRuleset(
                    StvRulesetParser.parseTieBreak(firstNonNull(tiebreak, fileConfig.tiebreak()), candidates));
            QpqResult result = QpqCounter.count(candidates, ballots, effectiveSeats, ruleset);
            return QpqTextReport.render(result);
        }

        StvResult result;
        RoundingRule roundingRuleForReport;
        switch (effectiveMethod) {
            case "stv" -> {
                StvRuleset ruleset = StvRulesetParser.parse(
                        firstNonNull(quota, fileConfig.quota()),
                        firstNonNull(transfer, fileConfig.transfer()),
                        firstNonNull(rounding, fileConfig.rounding()),
                        firstNonNull(tiebreak, fileConfig.tiebreak()),
                        fileConfig.batchExclusion(),
                        firstNonNull(tiedRankings, fileConfig.tiedRankings()),
                        fileConfig.minimumPreferences(),
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
                    "Unsupported method '" + effectiveMethod
                            + "'; expected 'stv', 'meek', 'schulze', 'borda', 'cpo-stv', or 'qpq'");
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

    /**
     * Schulze and Borda don't fill seats the way STV/Meek do -- they just rank every candidate
     * -- so neither needs {@code --seats}, and only a text report exists for either so far
     * (see {@link SchulzeTextReport}, {@link BordaTextReport}); {@code --report-format}
     * csv/json/html isn't supported for these two methods yet.
     */
    private String runNonStvMethod(String effectiveMethod, List<Candidate> candidates, List<Ballot> ballots) {
        String resolvedReportFormat = firstNonNull(reportFormat, "text").toLowerCase(Locale.ROOT);
        if (!resolvedReportFormat.equals("text")) {
            throw new IllegalArgumentException(
                    "--report-format '" + resolvedReportFormat + "' isn't supported yet for --method " + effectiveMethod
                            + "; only 'text' is");
        }
        return switch (effectiveMethod) {
            case "schulze" -> {
                SchulzeResult result = SchulzeMethod.count(candidates, ballots);
                yield SchulzeTextReport.render(candidates, result);
            }
            case "borda" -> {
                BordaScoringRule rule = parseBordaScoring(firstNonNull(bordaScoring, "classic"));
                BordaResult result = BordaMethod.count(candidates, ballots, rule);
                yield BordaTextReport.render(result);
            }
            default -> throw new IllegalStateException("unreachable: " + effectiveMethod);
        };
    }

    private static BordaScoringRule parseBordaScoring(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "classic" -> ClassicBorda.INSTANCE;
            case "dowdall" -> DowdallScoring.INSTANCE;
            default -> throw new IllegalArgumentException(
                    "Unknown --borda-scoring '" + value + "'; expected 'classic' or 'dowdall'");
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
