package info.fkhodkov.rankedchoice.count;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import info.fkhodkov.rankedchoice.borda.BordaMethod;
import info.fkhodkov.rankedchoice.borda.BordaReports;
import info.fkhodkov.rankedchoice.borda.BordaResult;
import info.fkhodkov.rankedchoice.borda.BordaScoringRule;
import info.fkhodkov.rankedchoice.borda.BordaTextReport;
import info.fkhodkov.rankedchoice.borda.ClassicBorda;
import info.fkhodkov.rankedchoice.borda.DowdallScoring;
import info.fkhodkov.rankedchoice.cambridge.CambridgeCounter;
import info.fkhodkov.rankedchoice.cambridge.CambridgeRuleset;
import info.fkhodkov.rankedchoice.core.config.ElectionConfig;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.cpostv.CpoStvCounter;
import info.fkhodkov.rankedchoice.cpostv.CpoStvReports;
import info.fkhodkov.rankedchoice.cpostv.CpoStvResult;
import info.fkhodkov.rankedchoice.cpostv.CpoStvRuleset;
import info.fkhodkov.rankedchoice.cpostv.CpoStvTextReport;
import info.fkhodkov.rankedchoice.qpq.QpqCounter;
import info.fkhodkov.rankedchoice.qpq.QpqReports;
import info.fkhodkov.rankedchoice.qpq.QpqResult;
import info.fkhodkov.rankedchoice.qpq.QpqRuleset;
import info.fkhodkov.rankedchoice.qpq.QpqTextReport;
import info.fkhodkov.rankedchoice.schulze.SchulzeMethod;
import info.fkhodkov.rankedchoice.schulze.SchulzeReports;
import info.fkhodkov.rankedchoice.schulze.SchulzeResult;
import info.fkhodkov.rankedchoice.schulze.SchulzeTextReport;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeProportionalRanking;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeProportionalReports;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeProportionalResult;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeStvCounter;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeStvReports;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeStvResult;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeStvTextReport;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.TiedRankingPolicy;
import info.fkhodkov.rankedchoice.stv.config.StvRulesetParser;
import info.fkhodkov.rankedchoice.stv.meek.KeepRule;
import info.fkhodkov.rankedchoice.stv.meek.MeekCounter;
import info.fkhodkov.rankedchoice.stv.meek.MeekRuleset;
import info.fkhodkov.rankedchoice.stv.report.StvCsvReport;
import info.fkhodkov.rankedchoice.stv.report.StvHtmlReport;
import info.fkhodkov.rankedchoice.stv.report.StvJsonReport;
import info.fkhodkov.rankedchoice.stv.report.StvTextReport;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * Counts an election by any method, the way the command line does: by STV, Meek's or Warren's
 * method, Cambridge STV, CPO-STV, QPQ, Schulze, Schulze STV, the Schulze proportional ranking or
 * Borda. For the STV family, rules come from the options, else a config or bundled ruleset, else
 * the defaults (Droop quota, fractional transfer, exact rounding, declared-order tie-break). Every
 * method refuses an option it would otherwise ignore, so a typo or a misremembered rule can't pass
 * unnoticed.
 *
 * <p>{@link #of} settles the options and the rules, {@link #count} counts an election by them.
 */
public final class Tabulator {

    /** A counting method, by the name the options give it, and what it is. */
    public record Method(String name, String description) {
    }

    /** Every method, the default (classic STV) first. */
    public static final List<Method> METHODS = List.of(
            new Method("stv", "Classic STV: a quota, surplus transfers and exclusions, by the rules chosen"),
            new Method("meek", "Meek's method: every ballot recounted each round against converging keep factors"),
            new Method("warren", "Warren's method: Meek's count, but an elected candidate takes a flat amount"),
            new Method("cambridge", "Cambridge STV: whole ballots, surpluses by the Cincinnati method"),
            new Method("qpq", "Woodall's Quota Preferential by Quotient: whole votes, quotients, restarts"),
            new Method("cpo-stv", "Tideman's CPO-STV: every possible committee compared with every other"),
            new Method("schulze-stv", "Schulze STV: committees compared by how well they split the voters"),
            new Method("schulze-proportional", "The Schulze proportional ranking: a party list, place by place"),
            new Method("schulze", "Schulze: a Condorcet beatpath ranking of every candidate"),
            new Method("borda", "Borda: positional scores, classic or Dowdall"));

    private static final ElectionConfig EMPTY_CONFIG =
            new ElectionConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);

    /**
     * The options each method has no use for, with why. Only options are checked: a config file or
     * ruleset may legitimately carry fields another method uses.
     */
    private static final Map<String, Map<String, String>> UNUSED_OPTIONS = Map.of(
            "stv", Map.of(
                    "--precision", "only meek and warren use it",
                    "--borda-scoring", "only borda uses it",
                    "--given-list", "only schulze-proportional uses it"),
            "meek", Map.of(
                    "--quota", "the method fixes its own quota",
                    "--transfer", "the method fixes its own transfers",
                    "--borda-scoring", "only borda uses it",
                    "--given-list", "only schulze-proportional uses it"),
            "warren", Map.of(
                    "--quota", "the method fixes its own quota",
                    "--transfer", "the method fixes its own transfers",
                    "--borda-scoring", "only borda uses it",
                    "--given-list", "only schulze-proportional uses it"),
            "cambridge", Map.of(
                    "--quota", "the method fixes its own quota",
                    "--transfer", "the method moves whole ballots by the Cincinnati method",
                    "--rounding", "it counts whole ballots, so there's nothing to round",
                    "--precision", "only meek and warren use it",
                    "--borda-scoring", "only borda uses it",
                    "--given-list", "only schulze-proportional uses it"),
            "qpq", Map.of(
                    "--quota", "its quota is a formula recalculated every stage",
                    "--transfer", "votes are never transferred, only credited",
                    "--rounding", "every quotient is exact",
                    "--precision", "only meek and warren use it",
                    "--borda-scoring", "only borda uses it",
                    "--given-list", "only schulze-proportional uses it"),
            "cpo-stv", Map.of(
                    "--tiebreak", "it compares whole committees, with no candidate-by-candidate decisions",
                    "--precision", "only meek and warren use it",
                    "--borda-scoring", "only borda uses it",
                    "--given-list", "only schulze-proportional uses it"),
            "schulze-stv", Map.of(
                    "--ruleset", "rulesets set STV rules",
                    "--quota", "it has no quota: committees are compared by their support",
                    "--transfer", "votes are never transferred",
                    "--rounding", "every support is exact",
                    "--tiebreak", "ties are broken by the single-winner Schulze ranking",
                    "--precision", "only meek and warren use it",
                    "--tied-rankings", "it counts tied rankings natively, by proportional completion",
                    "--minimum-preferences", "it counts every ballot, completing what's unranked",
                    "--borda-scoring", "only borda uses it",
                    "--given-list", "only schulze-proportional uses it"),
            "schulze-proportional", Map.of(
                    "--seats", "it ranks every candidate rather than filling seats",
                    "--ruleset", "rulesets set STV rules",
                    "--quota", "it has no quota: places are filled by comparing supports",
                    "--transfer", "votes are never transferred",
                    "--rounding", "every support is exact",
                    "--tiebreak", "ties are broken by the single-winner Schulze ranking",
                    "--precision", "only meek and warren use it",
                    "--tied-rankings", "it counts tied rankings natively, by proportional completion",
                    "--minimum-preferences", "it counts every ballot, completing what's unranked",
                    "--borda-scoring", "only borda uses it"),
            "schulze", nonStvUnused("--borda-scoring", "only borda uses it"),
            "borda", nonStvUnused(null, null));

    /**
     * The options (by their command-line names, {@code --quota} and so on) the method refuses,
     * each with why; none for an unknown method.
     */
    public static Map<String, String> unusedOptions(String method) {
        return UNUSED_OPTIONS.getOrDefault(method.toLowerCase(Locale.ROOT), Map.of());
    }

    private final CountOptions options;
    private final ElectionConfig config;
    private final String method;

    private Tabulator(CountOptions options, ElectionConfig config, String method) {
        this.options = options;
        this.config = config;
        this.method = method;
    }

    /**
     * Settles a count's options: the rules come from {@code config} (a config file's, or null), or
     * else the bundled ruleset the options name, and the method must have a use for every option
     * given. An unknown method is refused only by {@link #count}.
     */
    public static Tabulator of(CountOptions options, ElectionConfig config) {
        if (config != null && options.ruleset() != null) {
            throw new IllegalArgumentException("--config and --ruleset are mutually exclusive; pass only one");
        }
        ElectionConfig rules = config != null ? config
                : options.ruleset() != null ? Rulesets.load(options.ruleset())
                : EMPTY_CONFIG;
        String method = firstNonNull(options.method(), "stv").toLowerCase(Locale.ROOT);
        refuseUnusedOptions(method, options);
        return new Tabulator(options, rules, method);
    }

    /** The rules from the config or ruleset, before the options override them. */
    public ElectionConfig config() {
        return config;
    }

    /** The method, lower-cased: stv where none was given. */
    public String method() {
        return method;
    }

    /**
     * Counts the election. Classic STV with exact arithmetic tells {@code warnings} when its
     * figures grow so long the count may become very slow.
     */
    public Tabulation count(Election election, Consumer<String> warnings) {
        List<Candidate> candidates = election.candidates();
        List<Ballot> ballots = election.ballots();
        // Refused before counting, rather than by the report after.
        Candidate.requireDistinctNames(candidates);
        boolean seatsBased = !method.equals("schulze") && !method.equals("borda")
                && !method.equals("schulze-proportional");
        Integer seats = firstNonNull(options.seats(), config.seats());
        if (seats == null) {
            seats = election.seats();
        }
        if (seatsBased && seats == null) {
            throw new IllegalArgumentException("--seats is required for CSV input");
        }
        String tiedRankings = firstNonNull(options.tiedRankings(), config.tiedRankings());
        Integer configuredMinimumPreferences = firstNonNull(options.minimumPreferences(), config.minimumPreferences());
        int minimumPreferences = configuredMinimumPreferences == null ? 0 : configuredMinimumPreferences;

        switch (method) {
            case "schulze" -> {
                SchulzeResult result = SchulzeMethod.count(candidates, ballots);
                return new Tabulation(format -> switch (format) {
                    case CSV -> SchulzeReports.csv(candidates, result);
                    case JSON -> SchulzeReports.json(candidates, result);
                    case HTML -> SchulzeReports.html(candidates, result);
                    case TEXT -> SchulzeTextReport.render(candidates, result);
                });
            }
            case "borda" -> {
                BordaScoringRule rule = parseBordaScoring(firstNonNull(options.bordaScoring(), "classic"));
                BordaResult result = BordaMethod.count(candidates, ballots, rule);
                return new Tabulation(format -> switch (format) {
                    case CSV -> BordaReports.csv(result);
                    case JSON -> BordaReports.json(result);
                    case HTML -> BordaReports.html(result);
                    case TEXT -> BordaTextReport.render(result);
                });
            }
            case "cpo-stv" -> {
                CpoStvRuleset ruleset = new CpoStvRuleset(
                        StvRulesetParser.parseQuota(firstNonNull(options.quota(), config.quota())),
                        StvRulesetParser.parseTransfer(firstNonNull(options.transfer(), config.transfer())),
                        StvRulesetParser.parseRounding(firstNonNull(options.rounding(), config.rounding())),
                        StvRulesetParser.parseTiedRankings(tiedRankings, TiedRankingPolicy.REJECT),
                        minimumPreferences);
                CpoStvResult result = CpoStvCounter.count(candidates, ballots, seats, ruleset);
                return new Tabulation(format -> switch (format) {
                    case CSV -> CpoStvReports.csv(result);
                    case JSON -> CpoStvReports.json(result);
                    case HTML -> CpoStvReports.html(result);
                    case TEXT -> CpoStvTextReport.render(result);
                });
            }
            case "schulze-proportional" -> {
                SchulzeProportionalResult result = SchulzeProportionalRanking.count(candidates, ballots,
                        givenList(candidates));
                return new Tabulation(format -> switch (format) {
                    case CSV -> SchulzeProportionalReports.csv(result);
                    case JSON -> SchulzeProportionalReports.json(result);
                    case HTML -> SchulzeProportionalReports.html(result);
                    case TEXT -> SchulzeProportionalReports.text(result);
                });
            }
            case "schulze-stv" -> {
                SchulzeStvResult result = SchulzeStvCounter.count(candidates, ballots, seats);
                return new Tabulation(format -> switch (format) {
                    case CSV -> SchulzeStvReports.csv(result);
                    case JSON -> SchulzeStvReports.json(result);
                    case HTML -> SchulzeStvReports.html(result);
                    case TEXT -> SchulzeStvTextReport.render(result);
                });
            }
            case "qpq" -> {
                QpqRuleset ruleset = new QpqRuleset(
                        StvRulesetParser.parseTieBreak(firstNonNull(options.tiebreak(), config.tiebreak()), candidates),
                        StvRulesetParser.parseTiedRankings(tiedRankings, TiedRankingPolicy.REJECT),
                        minimumPreferences);
                QpqResult result = QpqCounter.count(candidates, ballots, seats, ruleset);
                return new Tabulation(format -> switch (format) {
                    case CSV -> QpqReports.csv(result);
                    case JSON -> QpqReports.json(result);
                    case HTML -> QpqReports.html(result);
                    case TEXT -> QpqTextReport.render(result);
                });
            }
            case "stv" -> {
                StvRuleset ruleset = StvRulesetParser.parse(
                        firstNonNull(options.quota(), config.quota()),
                        firstNonNull(options.transfer(), config.transfer()),
                        firstNonNull(options.rounding(), config.rounding()),
                        firstNonNull(options.tiebreak(), config.tiebreak()),
                        config.batchExclusion(),
                        tiedRankings,
                        configuredMinimumPreferences,
                        config.deferSurpluses(),
                        config.votesRequired(),
                        config.surplusOrder(),
                        config.electAsTheyStand(),
                        config.electSureWinners(),
                        config.exclusionOrder(),
                        config.simultaneousSurpluses(),
                        config.mathematicalElimination(),
                        config.quotaEachRound(),
                        config.rankingsElimination(),
                        candidates);
                return stv(StvCounter.count(candidates, ballots, seats, ruleset, warnings), ruleset.roundingRule());
            }
            case "meek", "warren" -> {
                RoundingRule rounding =
                        StvRulesetParser.parseRounding(firstNonNull(options.rounding(), config.rounding()));
                Integer precision = firstNonNull(options.precision(), config.precision());
                MeekRuleset ruleset = new MeekRuleset(rounding,
                        StvRulesetParser.parseTieBreak(firstNonNull(options.tiebreak(), config.tiebreak()), candidates),
                        StvRulesetParser.parseTiedRankings(tiedRankings, TiedRankingPolicy.SPLIT),
                        minimumPreferences,
                        precision != null ? precision : MeekRuleset.DEFAULT_PRECISION,
                        method.equals("warren") ? KeepRule.WARREN : KeepRule.MEEK);
                return stv(MeekCounter.count(candidates, ballots, seats, ruleset), rounding);
            }
            case "cambridge" -> {
                CambridgeRuleset ruleset = new CambridgeRuleset(
                        StvRulesetParser.parseTieBreak(firstNonNull(options.tiebreak(), config.tiebreak()), candidates),
                        StvRulesetParser.parseTiedRankings(tiedRankings, TiedRankingPolicy.REJECT),
                        minimumPreferences);
                return stv(CambridgeCounter.count(candidates, ballots, seats, ruleset), Exact.INSTANCE);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported method '" + method
                            + "'; expected 'stv', 'meek', 'warren', 'cambridge', 'schulze', 'borda', 'cpo-stv', "
                            + "'schulze-stv', 'schulze-proportional', or 'qpq'");
        }
    }

    private static Tabulation stv(StvResult result, RoundingRule rounding) {
        return new Tabulation(format -> switch (format) {
            case CSV -> StvCsvReport.render(result, rounding);
            case JSON -> StvJsonReport.render(result, rounding);
            case HTML -> StvHtmlReport.render(result, rounding);
            case TEXT -> StvTextReport.render(result, rounding);
        });
    }

    private static Map<String, String> nonStvUnused(String extra, String extraReason) {
        Map<String, String> unused = new LinkedHashMap<>();
        unused.put("--seats", "it ranks every candidate rather than filling seats");
        unused.put("--ruleset", "rulesets set STV rules");
        for (String stvOption : List.of("--quota", "--transfer", "--rounding", "--tiebreak", "--precision")) {
            unused.put(stvOption, "it's an STV option");
        }
        unused.put("--tied-rankings", "it counts tied rankings natively");
        unused.put("--minimum-preferences", "it counts every ballot as ranked");
        if (extra != null) {
            unused.put(extra, extraReason);
        }
        unused.put("--given-list", "only schulze-proportional uses it");
        return unused;
    }

    private static void refuseUnusedOptions(String method, CountOptions options) {
        Map<String, String> unused = UNUSED_OPTIONS.get(method);
        if (unused == null) {
            return; // an unknown method is reported once its count is attempted
        }
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("--seats", options.seats());
        given.put("--ruleset", options.ruleset());
        given.put("--quota", options.quota());
        given.put("--transfer", options.transfer());
        given.put("--rounding", options.rounding());
        given.put("--tiebreak", options.tiebreak());
        given.put("--tied-rankings", options.tiedRankings());
        given.put("--minimum-preferences", options.minimumPreferences());
        given.put("--precision", options.precision());
        given.put("--borda-scoring", options.bordaScoring());
        given.put("--given-list", options.givenList());
        for (Map.Entry<String, Object> option : given.entrySet()) {
            if (option.getValue() != null && unused.containsKey(option.getKey())) {
                throw new IllegalArgumentException(option.getKey() + " doesn't apply to --method " + method
                        + ": " + unused.get(option.getKey()));
            }
        }
    }

    /** The candidates {@code --given-list} names, in its order. */
    private List<Candidate> givenList(List<Candidate> candidates) {
        if (options.givenList() == null) {
            return List.of();
        }
        List<String> names = options.givenList();
        for (int i = 0; i < names.size(); i++) {
            if (names.subList(0, i).contains(names.get(i))) {
                throw new IllegalArgumentException("--given-list names " + names.get(i) + " twice");
            }
        }
        return names.stream().map(name -> candidates.stream()
                .filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("--given-list names " + name
                        + ", who isn't a candidate"))).toList();
    }

    private static BordaScoringRule parseBordaScoring(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "classic" -> ClassicBorda.INSTANCE;
            case "dowdall" -> DowdallScoring.INSTANCE;
            default -> throw new IllegalArgumentException(
                    "Unknown --borda-scoring '" + value + "'; expected 'classic' or 'dowdall'");
        };
    }

    private static <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
