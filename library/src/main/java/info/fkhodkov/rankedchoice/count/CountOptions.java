package info.fkhodkov.rankedchoice.count;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The options of a count, each null where it isn't given. They're named, in errors, by the command
 * line's flags ({@code --quota} and so on). A given option overrides the config or ruleset's.
 *
 * <p>Start from {@link #NONE} and set what's wanted, each {@code with...} giving new options:
 * <pre>{@code
 * CountOptions options = CountOptions.NONE.withMethod("meek").withSeats(2);
 * }</pre>
 * A new option will only add methods, so code written this way keeps compiling.
 */
public final class CountOptions {

    /** No options: an stv count by the defaults. */
    public static final CountOptions NONE =
            new CountOptions(null, null, null, null, null, null, null, null, null, null, null, null);

    private final String method;
    private final String ruleset;
    private final Integer seats;
    private final String quota;
    private final String transfer;
    private final String rounding;
    private final String tiebreak;
    private final String tiedRankings;
    private final Integer minimumPreferences;
    private final Integer precision;
    private final String bordaScoring;
    private final List<String> givenList;

    private CountOptions(
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
            List<String> givenList) {
        this.method = method;
        this.ruleset = ruleset;
        this.seats = seats;
        this.quota = quota;
        this.transfer = transfer;
        this.rounding = rounding;
        this.tiebreak = tiebreak;
        this.tiedRankings = tiedRankings;
        this.minimumPreferences = minimumPreferences;
        this.precision = precision;
        this.bordaScoring = bordaScoring;
        // Copied without List.copyOf, which refuses a null: Tabulator names it as it does any other non-candidate.
        this.givenList = givenList == null ? null : Collections.unmodifiableList(new ArrayList<>(givenList));
    }

    /**
     * stv (the default), meek, warren, cambridge, schulze, borda, cpo-stv, schulze-stv,
     * schulze-proportional or qpq.
     */
    public String method() {
        return method;
    }

    /** These options with {@code method} set (null to leave it unset). */
    public CountOptions withMethod(String method) {
        return new CountOptions(method, ruleset, seats, quota, transfer, rounding, tiebreak, tiedRankings,
                minimumPreferences, precision, bordaScoring, givenList);
    }

    /** A bundled ruleset's name ({@link Rulesets}); not with a config of one's own. */
    public String ruleset() {
        return ruleset;
    }

    /** These options with {@code ruleset} set (null to leave it unset). */
    public CountOptions withRuleset(String ruleset) {
        return new CountOptions(method, ruleset, seats, quota, transfer, rounding, tiebreak, tiedRankings,
                minimumPreferences, precision, bordaScoring, givenList);
    }

    /** The seats to fill, over the config's and the ballot file's. */
    public Integer seats() {
        return seats;
    }

    /** These options with {@code seats} set (null to leave it unset). */
    public CountOptions withSeats(Integer seats) {
        return new CountOptions(method, ruleset, seats, quota, transfer, rounding, tiebreak, tiedRankings,
                minimumPreferences, precision, bordaScoring, givenList);
    }

    /** As {@code --quota}. */
    public String quota() {
        return quota;
    }

    /** These options with {@code quota} set (null to leave it unset). */
    public CountOptions withQuota(String quota) {
        return new CountOptions(method, ruleset, seats, quota, transfer, rounding, tiebreak, tiedRankings,
                minimumPreferences, precision, bordaScoring, givenList);
    }

    /** As {@code --transfer}. */
    public String transfer() {
        return transfer;
    }

    /** These options with {@code transfer} set (null to leave it unset). */
    public CountOptions withTransfer(String transfer) {
        return new CountOptions(method, ruleset, seats, quota, transfer, rounding, tiebreak, tiedRankings,
                minimumPreferences, precision, bordaScoring, givenList);
    }

    /** As {@code --rounding}. */
    public String rounding() {
        return rounding;
    }

    /** These options with {@code rounding} set (null to leave it unset). */
    public CountOptions withRounding(String rounding) {
        return new CountOptions(method, ruleset, seats, quota, transfer, rounding, tiebreak, tiedRankings,
                minimumPreferences, precision, bordaScoring, givenList);
    }

    /** As {@code --tiebreak}. */
    public String tiebreak() {
        return tiebreak;
    }

    /** These options with {@code tiebreak} set (null to leave it unset). */
    public CountOptions withTiebreak(String tiebreak) {
        return new CountOptions(method, ruleset, seats, quota, transfer, rounding, tiebreak, tiedRankings,
                minimumPreferences, precision, bordaScoring, givenList);
    }

    /** As {@code --tied-rankings}. */
    public String tiedRankings() {
        return tiedRankings;
    }

    /** These options with {@code tiedRankings} set (null to leave it unset). */
    public CountOptions withTiedRankings(String tiedRankings) {
        return new CountOptions(method, ruleset, seats, quota, transfer, rounding, tiebreak, tiedRankings,
                minimumPreferences, precision, bordaScoring, givenList);
    }

    /** As {@code --minimum-preferences}. */
    public Integer minimumPreferences() {
        return minimumPreferences;
    }

    /** These options with {@code minimumPreferences} set (null to leave it unset). */
    public CountOptions withMinimumPreferences(Integer minimumPreferences) {
        return new CountOptions(method, ruleset, seats, quota, transfer, rounding, tiebreak, tiedRankings,
                minimumPreferences, precision, bordaScoring, givenList);
    }

    /** As {@code --precision} (meek and warren). */
    public Integer precision() {
        return precision;
    }

    /** These options with {@code precision} set (null to leave it unset). */
    public CountOptions withPrecision(Integer precision) {
        return new CountOptions(method, ruleset, seats, quota, transfer, rounding, tiebreak, tiedRankings,
                minimumPreferences, precision, bordaScoring, givenList);
    }

    /** classic or dowdall (borda). */
    public String bordaScoring() {
        return bordaScoring;
    }

    /** These options with {@code bordaScoring} set (null to leave it unset). */
    public CountOptions withBordaScoring(String bordaScoring) {
        return new CountOptions(method, ruleset, seats, quota, transfer, rounding, tiebreak, tiedRankings,
                minimumPreferences, precision, bordaScoring, givenList);
    }

    /**
     * The candidates, by name, who take the first places in order, each place's comparisons
     * still reported (schulze-proportional); an unmodifiable copy of the list given.
     */
    public List<String> givenList() {
        return givenList;
    }

    /** These options with {@code givenList} set (null to leave it unset). */
    public CountOptions withGivenList(List<String> givenList) {
        return new CountOptions(method, ruleset, seats, quota, transfer, rounding, tiebreak, tiedRankings,
                minimumPreferences, precision, bordaScoring, givenList);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CountOptions other)) {
            return false;
        }
        return Objects.equals(method, other.method)
                && Objects.equals(ruleset, other.ruleset)
                && Objects.equals(seats, other.seats)
                && Objects.equals(quota, other.quota)
                && Objects.equals(transfer, other.transfer)
                && Objects.equals(rounding, other.rounding)
                && Objects.equals(tiebreak, other.tiebreak)
                && Objects.equals(tiedRankings, other.tiedRankings)
                && Objects.equals(minimumPreferences, other.minimumPreferences)
                && Objects.equals(precision, other.precision)
                && Objects.equals(bordaScoring, other.bordaScoring)
                && Objects.equals(givenList, other.givenList);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, ruleset, seats, quota, transfer, rounding, tiebreak, tiedRankings,
                minimumPreferences, precision, bordaScoring, givenList);
    }

    /** As a record's would be, every field named. */
    @Override
    public String toString() {
        return "CountOptions["
                + "method=" + method
                + ", ruleset=" + ruleset
                + ", seats=" + seats
                + ", quota=" + quota
                + ", transfer=" + transfer
                + ", rounding=" + rounding
                + ", tiebreak=" + tiebreak
                + ", tiedRankings=" + tiedRankings
                + ", minimumPreferences=" + minimumPreferences
                + ", precision=" + precision
                + ", bordaScoring=" + bordaScoring
                + ", givenList=" + givenList
                + "]";
    }

}
