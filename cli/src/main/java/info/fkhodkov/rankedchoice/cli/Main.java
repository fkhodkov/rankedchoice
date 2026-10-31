package info.fkhodkov.rankedchoice.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import info.fkhodkov.rankedchoice.core.ballot.BallotParseException;
import info.fkhodkov.rankedchoice.core.config.ConfigLoader;
import info.fkhodkov.rankedchoice.core.config.ElectionConfig;
import info.fkhodkov.rankedchoice.count.CountOptions;
import info.fkhodkov.rankedchoice.count.Election;
import info.fkhodkov.rankedchoice.count.ReportFormat;
import info.fkhodkov.rankedchoice.count.Tabulator;

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
 *
 * <p>The count itself is the library's {@link Tabulator}; this class reads the command line and the
 * files it names, and writes the report.
 */
@Command(name = "rankedchoice", mixinStandardHelpOptions = true,
        description = "Tabulate a ranked-choice election from a ballot file.")
public final class Main implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, required = true, description = "Ballot file (.blt or .csv)")
    private Path input;

    @Option(names = {"-m", "--method"},
            description = "Counting method: stv|meek|warren|cambridge|schulze|borda|cpo-stv|schulze-stv|"
            + "schulze-proportional|qpq. "
            + "Defaults to stv. --quota/--transfer don't apply to meek or warren (their quota and transfer are fixed "
            + "by the method itself); schulze/borda don't use --seats, --quota, --transfer, --rounding, or "
            + "--tiebreak at all; cpo-stv uses --seats/--quota/--transfer/--rounding but not --tiebreak; "
            + "schulze-stv uses only --seats; schulze-proportional (a party list, every candidate ranked) uses "
            + "none of them; qpq (Woodall's Quota "
            + "Preferential by Quotient -- unrelated to the hare-clark-qpq *ruleset*, a different, classic-STV "
            + "preset despite the similar name) uses --seats/--tiebreak but not --quota/--transfer/--rounding, "
            + "since its quota and transfer are both derived formulas, not configurable.")
    private String method;

    @Option(names = {"--borda-scoring"}, description = "classic|dowdall. Only applies to --method borda; "
            + "defaults to classic. 'dowdall' is the Nauru system (1, 1/2, 1/3, ...).")
    private String bordaScoring;

    @Option(names = {"--given-list"}, split = ",", paramLabel = "NAME[,NAME...]",
            description = "Only applies to --method schulze-proportional: these candidates take the first "
            + "places, in this order, each place still showing its comparisons and the method's own potential "
            + "winners, so a list chosen otherwise can be checked place by place. The method fills the rest.")
    private List<String> givenList;

    @Option(names = {"-f", "--format"},
            description = "Input format: blt or csv. Inferred from the file extension if omitted.")
    private String format;

    @Option(names = {"-s", "--seats"},
            description = "Seats to fill. Required for CSV input; for BLT input, overrides the file's own seat count "
                    + "if given.")
    private Integer seats;

    @Option(names = {"-o", "--output"}, description = "Output file. Defaults to standard output.")
    private Path output;

    @Option(names = {"--report-format"},
            description = "Report output format: text|csv|json|html. Defaults to text; inferred from "
                    + "--output's extension if that's given and this isn't.")
    private String reportFormat;

    @Option(names = {"-c", "--config"},
            description = "YAML config file with quota/transfer/rounding/tiebreak/seats/format.")
    private Path config;

    @Option(names = {"-r", "--ruleset"}, description = "A bundled ruleset preset: scottish|ers97|ers76|"
            + "hare-clark-qpq|act-hare-clark|senate|nsw-lg|vic-lc|wa-lc|portland|minneapolis|albany|irv. "
            + "Mutually exclusive with --config.")
    private String rulesetName;

    @Option(names = {"--quota"},
            description = "droop|hare|hagenbach-bischoff|imperiali|droop-rounded:N. Overrides --config/--ruleset.")
    private String quota;

    @Option(names = {"--transfer"},
            description = "fractional|wigm|last-parcel|last-parcel-deferred-rounding|unweighted-inclusive|"
                    + "unweighted-exclusive:N|weighted-transferable|weighted-inclusive|fractional-truncated. "
                    + "Overrides --config/--ruleset.")
    private String transfer;

    @Option(names = {"--rounding"}, description = "exact|truncate:N. Defaults to exact, which can grow very slow on "
            + "large counts with long rankings and many seats (a warning is printed if it starts to); truncate:9 "
            + "keeps figures bounded. Overrides --config/--ruleset.")
    private String rounding;

    @Option(names = {"--tiebreak"},
            description = "Comma-separated fallback chain: declared|previous-round|first-preference|"
                    + "seeded-random:SEED, e.g. 'previous-round,declared'. Overrides --config/--ruleset.")
    private String tiebreak;

    @Option(names = {"--tied-rankings"},
            description = "What to do with a ballot ranking several candidates equally: reject|split|truncate|skip. "
                    + "split divides the ballot between them (stv with fractional transfer, meek, and warren); "
                    + "truncate stops the ballot at the tie (a tie at first preference voids it, as Scottish law "
                    + "requires); skip drops the tied rank and continues. Defaults to split for meek, warren, and stv "
                    + "under fractional transfer, reject otherwise. Applies to stv, meek, warren, cambridge, cpo-stv, "
                    + "and qpq (schulze, schulze-stv and borda count ties natively). Overrides --config/--ruleset.")
    private String tiedRankings;

    @Option(names = {"--minimum-preferences"},
            description = "Treat a ballot ranking fewer candidates than this (after tied rankings are dealt with) as "
                    + "informal, capped at one fewer than the number of candidates. Defaults to none; the "
                    + "hare-clark-qpq preset sets 7. Applies to stv, meek, warren, cambridge, cpo-stv, and qpq. "
                    + "Overrides --config/--ruleset.")
    private Integer minimumPreferences;

    @Option(names = {"--precision"},
            description = "Decimal places a meek or warren count works to (default 9, as droop's fixed-point "
                    + "Meek). Only shapes the count itself; --rounding still controls how figures are reported. "
                    + "Overrides --config.")
    private Integer precision;

    static void main(String[] args) {
        endOnAnUncaughtError();
        System.exit(new CommandLine(new Main()).execute(args));
    }

    /**
     * Ends the program as soon as any thread dies of an error nobody caught. A parallel count's
     * worker out of memory can die before it marks its task failed, and the count then waits for
     * that task forever, idle, with too little memory left even to act on Ctrl-C or {@code kill}
     * (Schulze STV 20 for 5 in 128 MB did, 2 runs of 6). The message is made beforehand and the
     * program halted, since nothing more can be allocated: what the handler uses is loaded and
     * linked here, as each first use, on a full heap, failed (the class {@link Runtime#halt} uses,
     * the compare-and-set, the write).
     */
    static void endOnAnUncaughtError() {
        try {
            Class.forName("java.lang.Shutdown", true, null);
        } catch (ClassNotFoundException e) {
            // Another Java's: halt loads what it uses when it's called.
        }
        byte[] outOfMemory = (outOfMemoryMessage() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        FileOutputStream err = new FileOutputStream(FileDescriptor.err);
        AtomicBoolean told = new AtomicBoolean();
        told.compareAndSet(true, true);
        try {
            err.write(new byte[0]);
        } catch (IOException e) {
            // No standard error to write to: halting still works.
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            if (!told.compareAndSet(false, true)) {
                return; // another dying thread is saying it, and halts once it has
            }
            try {
                if (e instanceof OutOfMemoryError) {
                    err.write(outOfMemory);
                } else {
                    err.write(("Error: an internal fault, please report it: " + e + System.lineSeparator())
                            .getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
                // Halting matters more than the message.
            } finally {
                Runtime.getRuntime().halt(1);
            }
        });
    }

    private static String outOfMemoryMessage() {
        return "Error: the count ran out of memory (Java's heap is " + Runtime.getRuntime().maxMemory() / (1024 * 1024)
                + " MB); give it more with java -Xmx, e.g. java -Xmx8g -jar ...";
    }

    @Override
    public Integer call() {
        // Made before the count: once memory has run out, building it might fail too.
        String outOfMemory = outOfMemoryMessage();
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
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException
                | UncheckedIOException | BallotParseException e) {
            // What the count refuses on purpose: its message says what's wrong with the input.
            System.err.println("Error: " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            // Anything else (a null, an index out of range, an overflow) is a fault in this program.
            System.err.println("Error: an internal fault, please report it: " + e);
            return 1;
        } catch (OutOfMemoryError e) {
            System.err.println(outOfMemory);
            return 1;
        }
    }

    private String runCount() {
        ElectionConfig fileConfig = loadFileConfig();
        Tabulator tabulator = Tabulator.of(new CountOptions(method, rulesetName, seats, quota, transfer, rounding,
                tiebreak, tiedRankings, minimumPreferences, precision, bordaScoring, givenList), fileConfig);
        ReportFormat reportAs = resolveReportFormat(tabulator.config());
        requireFile(input, "input file");
        Election election = Election.read(input, firstNonNull(format, tabulator.config().format()));
        return tabulator.count(election, warning -> System.err.println("Warning: " + warning
                + " -- e.g. --rounding truncate:9")).report(reportAs);
    }

    /**
     * The report format, the same way for every method: {@code --report-format}, else the config
     * file's {@code reportFormat}, else {@code --output}'s extension, else text.
     */
    private ReportFormat resolveReportFormat(ElectionConfig rules) {
        String given = firstNonNull(reportFormat, rules.reportFormat());
        return ReportFormat.of(given != null ? given : inferReportFormat(output));
    }

    /**
     * Falls back to {@code text} when neither {@code --report-format} nor a recognized {@code --output} extension is
     * given.
     */
    private static String inferReportFormat(Path output) {
        if (output == null) {
            return "text";
        }
        String name = fileName(output);
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

    /** A config file's rules, or null where none is given (a bundled ruleset is the Tabulator's to load). */
    private ElectionConfig loadFileConfig() {
        if (config != null && rulesetName != null) {
            throw new IllegalArgumentException("--config and --ruleset are mutually exclusive; pass only one");
        }
        return config != null ? ConfigLoader.load(requireFile(config, "config file")) : null;
    }

    private static String firstNonNull(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    /** The path, if it names a file that exists; otherwise a clear error naming what it was meant to be. */
    private static Path requireFile(Path path, String what) {
        if (Files.isDirectory(path)) {
            throw new IllegalArgumentException("The " + what + " " + path + " is a directory, not a file");
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("The " + what + " " + path + " doesn't exist");
        }
        return path;
    }

    /** A path's file name, lower-cased, or "" for one that has none (a root, such as "/"). */
    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
    }
}
