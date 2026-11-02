package info.fkhodkov.rankedchoice.core.config;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The overridable pieces of an election run that can come from a YAML config file or a
 * bundled ruleset preset: every field is optional (null means "not specified here"), and
 * callers decide their own precedence when combining this with CLI flags and defaults. Each
 * field is the YAML setting of the same name (README.md's "Config file" lists them).
 *
 * <p>{@link ConfigLoader} reads one; to make one in code, start from {@link #NONE} and set what's
 * wanted, each {@code with...} giving a new config:
 * <pre>{@code
 * ElectionConfig config = ElectionConfig.NONE.withQuota("droop").withTransfer("wigm");
 * }</pre>
 * A new setting will only add methods, so code written this way keeps compiling.
 *
 * <p>Jackson writes one by its fields, in this order, as it wrote the record this once was.
 */
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE,
        isGetterVisibility = Visibility.NONE)
public final class ElectionConfig {

    /** Nothing specified. */
    public static final ElectionConfig NONE = new ElectionConfig(null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null);

    private final String quota;
    private final String transfer;
    private final String rounding;
    private final String tiebreak;
    private final Integer seats;
    private final String format;
    private final String reportFormat;
    private final Boolean batchExclusion;
    private final String tiedRankings;
    private final Integer minimumPreferences;
    private final Integer precision;
    private final Boolean deferSurpluses;
    private final Boolean votesRequired;
    private final String surplusOrder;
    private final Boolean electAsTheyStand;
    private final Boolean electSureWinners;
    private final String exclusionOrder;
    private final Boolean simultaneousSurpluses;
    private final Boolean mathematicalElimination;
    private final Boolean quotaEachRound;
    private final Boolean rankingsElimination;

    @JsonCreator
    private ElectionConfig(
            @JsonProperty("quota") String quota,
            @JsonProperty("transfer") String transfer,
            @JsonProperty("rounding") String rounding,
            @JsonProperty("tiebreak") String tiebreak,
            @JsonProperty("seats") Integer seats,
            @JsonProperty("format") String format,
            @JsonProperty("reportFormat") String reportFormat,
            @JsonProperty("batchExclusion") Boolean batchExclusion,
            @JsonProperty("tiedRankings") String tiedRankings,
            @JsonProperty("minimumPreferences") Integer minimumPreferences,
            @JsonProperty("precision") Integer precision,
            @JsonProperty("deferSurpluses") Boolean deferSurpluses,
            @JsonProperty("votesRequired") Boolean votesRequired,
            @JsonProperty("surplusOrder") String surplusOrder,
            @JsonProperty("electAsTheyStand") Boolean electAsTheyStand,
            @JsonProperty("electSureWinners") Boolean electSureWinners,
            @JsonProperty("exclusionOrder") String exclusionOrder,
            @JsonProperty("simultaneousSurpluses") Boolean simultaneousSurpluses,
            @JsonProperty("mathematicalElimination") Boolean mathematicalElimination,
            @JsonProperty("quotaEachRound") Boolean quotaEachRound,
            @JsonProperty("rankingsElimination") Boolean rankingsElimination) {
        this.quota = quota;
        this.transfer = transfer;
        this.rounding = rounding;
        this.tiebreak = tiebreak;
        this.seats = seats;
        this.format = format;
        this.reportFormat = reportFormat;
        this.batchExclusion = batchExclusion;
        this.tiedRankings = tiedRankings;
        this.minimumPreferences = minimumPreferences;
        this.precision = precision;
        this.deferSurpluses = deferSurpluses;
        this.votesRequired = votesRequired;
        this.surplusOrder = surplusOrder;
        this.electAsTheyStand = electAsTheyStand;
        this.electSureWinners = electSureWinners;
        this.exclusionOrder = exclusionOrder;
        this.simultaneousSurpluses = simultaneousSurpluses;
        this.mathematicalElimination = mathematicalElimination;
        this.quotaEachRound = quotaEachRound;
        this.rankingsElimination = rankingsElimination;
    }

    public String quota() {
        return quota;
    }

    /** This config with {@code quota} set (null to leave it unset). */
    public ElectionConfig withQuota(String quota) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public String transfer() {
        return transfer;
    }

    /** This config with {@code transfer} set (null to leave it unset). */
    public ElectionConfig withTransfer(String transfer) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public String rounding() {
        return rounding;
    }

    /** This config with {@code rounding} set (null to leave it unset). */
    public ElectionConfig withRounding(String rounding) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public String tiebreak() {
        return tiebreak;
    }

    /** This config with {@code tiebreak} set (null to leave it unset). */
    public ElectionConfig withTiebreak(String tiebreak) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public Integer seats() {
        return seats;
    }

    /** This config with {@code seats} set (null to leave it unset). */
    public ElectionConfig withSeats(Integer seats) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public String format() {
        return format;
    }

    /** This config with {@code format} set (null to leave it unset). */
    public ElectionConfig withFormat(String format) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public String reportFormat() {
        return reportFormat;
    }

    /** This config with {@code reportFormat} set (null to leave it unset). */
    public ElectionConfig withReportFormat(String reportFormat) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public Boolean batchExclusion() {
        return batchExclusion;
    }

    /** This config with {@code batchExclusion} set (null to leave it unset). */
    public ElectionConfig withBatchExclusion(Boolean batchExclusion) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public String tiedRankings() {
        return tiedRankings;
    }

    /** This config with {@code tiedRankings} set (null to leave it unset). */
    public ElectionConfig withTiedRankings(String tiedRankings) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public Integer minimumPreferences() {
        return minimumPreferences;
    }

    /** This config with {@code minimumPreferences} set (null to leave it unset). */
    public ElectionConfig withMinimumPreferences(Integer minimumPreferences) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public Integer precision() {
        return precision;
    }

    /** This config with {@code precision} set (null to leave it unset). */
    public ElectionConfig withPrecision(Integer precision) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public Boolean deferSurpluses() {
        return deferSurpluses;
    }

    /** This config with {@code deferSurpluses} set (null to leave it unset). */
    public ElectionConfig withDeferSurpluses(Boolean deferSurpluses) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public Boolean votesRequired() {
        return votesRequired;
    }

    /** This config with {@code votesRequired} set (null to leave it unset). */
    public ElectionConfig withVotesRequired(Boolean votesRequired) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public String surplusOrder() {
        return surplusOrder;
    }

    /** This config with {@code surplusOrder} set (null to leave it unset). */
    public ElectionConfig withSurplusOrder(String surplusOrder) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public Boolean electAsTheyStand() {
        return electAsTheyStand;
    }

    /** This config with {@code electAsTheyStand} set (null to leave it unset). */
    public ElectionConfig withElectAsTheyStand(Boolean electAsTheyStand) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public Boolean electSureWinners() {
        return electSureWinners;
    }

    /** This config with {@code electSureWinners} set (null to leave it unset). */
    public ElectionConfig withElectSureWinners(Boolean electSureWinners) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public String exclusionOrder() {
        return exclusionOrder;
    }

    /** This config with {@code exclusionOrder} set (null to leave it unset). */
    public ElectionConfig withExclusionOrder(String exclusionOrder) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public Boolean simultaneousSurpluses() {
        return simultaneousSurpluses;
    }

    /** This config with {@code simultaneousSurpluses} set (null to leave it unset). */
    public ElectionConfig withSimultaneousSurpluses(Boolean simultaneousSurpluses) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public Boolean mathematicalElimination() {
        return mathematicalElimination;
    }

    /** This config with {@code mathematicalElimination} set (null to leave it unset). */
    public ElectionConfig withMathematicalElimination(Boolean mathematicalElimination) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public Boolean quotaEachRound() {
        return quotaEachRound;
    }

    /** This config with {@code quotaEachRound} set (null to leave it unset). */
    public ElectionConfig withQuotaEachRound(Boolean quotaEachRound) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    public Boolean rankingsElimination() {
        return rankingsElimination;
    }

    /** This config with {@code rankingsElimination} set (null to leave it unset). */
    public ElectionConfig withRankingsElimination(Boolean rankingsElimination) {
        return new ElectionConfig(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ElectionConfig other)) {
            return false;
        }
        return Objects.equals(quota, other.quota)
                && Objects.equals(transfer, other.transfer)
                && Objects.equals(rounding, other.rounding)
                && Objects.equals(tiebreak, other.tiebreak)
                && Objects.equals(seats, other.seats)
                && Objects.equals(format, other.format)
                && Objects.equals(reportFormat, other.reportFormat)
                && Objects.equals(batchExclusion, other.batchExclusion)
                && Objects.equals(tiedRankings, other.tiedRankings)
                && Objects.equals(minimumPreferences, other.minimumPreferences)
                && Objects.equals(precision, other.precision)
                && Objects.equals(deferSurpluses, other.deferSurpluses)
                && Objects.equals(votesRequired, other.votesRequired)
                && Objects.equals(surplusOrder, other.surplusOrder)
                && Objects.equals(electAsTheyStand, other.electAsTheyStand)
                && Objects.equals(electSureWinners, other.electSureWinners)
                && Objects.equals(exclusionOrder, other.exclusionOrder)
                && Objects.equals(simultaneousSurpluses, other.simultaneousSurpluses)
                && Objects.equals(mathematicalElimination, other.mathematicalElimination)
                && Objects.equals(quotaEachRound, other.quotaEachRound)
                && Objects.equals(rankingsElimination, other.rankingsElimination);
    }

    @Override
    public int hashCode() {
        return Objects.hash(quota, transfer, rounding, tiebreak, seats, format, reportFormat, batchExclusion,
                tiedRankings, minimumPreferences, precision, deferSurpluses, votesRequired, surplusOrder,
                electAsTheyStand, electSureWinners, exclusionOrder, simultaneousSurpluses, mathematicalElimination,
                quotaEachRound, rankingsElimination);
    }

    /** As a record's would be, every field named. */
    @Override
    public String toString() {
        return "ElectionConfig["
                + "quota=" + quota
                + ", transfer=" + transfer
                + ", rounding=" + rounding
                + ", tiebreak=" + tiebreak
                + ", seats=" + seats
                + ", format=" + format
                + ", reportFormat=" + reportFormat
                + ", batchExclusion=" + batchExclusion
                + ", tiedRankings=" + tiedRankings
                + ", minimumPreferences=" + minimumPreferences
                + ", precision=" + precision
                + ", deferSurpluses=" + deferSurpluses
                + ", votesRequired=" + votesRequired
                + ", surplusOrder=" + surplusOrder
                + ", electAsTheyStand=" + electAsTheyStand
                + ", electSureWinners=" + electSureWinners
                + ", exclusionOrder=" + exclusionOrder
                + ", simultaneousSurpluses=" + simultaneousSurpluses
                + ", mathematicalElimination=" + mathematicalElimination
                + ", quotaEachRound=" + quotaEachRound
                + ", rankingsElimination=" + rankingsElimination
                + "]";
    }

}
