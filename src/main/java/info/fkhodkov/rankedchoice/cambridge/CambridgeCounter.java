package info.fkhodkov.rankedchoice.cambridge;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.BallotFormality;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;

/**
 * Runs an STV count by the rules Cambridge, Massachusetts uses for its City Council and School
 * Committee (Massachusetts General Laws chapter 54A, with surpluses transferred by the
 * "Cincinnati method"), following OpenSTV's {@code CambridgeSTV} step for step. Every ballot
 * counts whole for one candidate at a time -- there are no fractional values anywhere -- and,
 * unlike every other method here, which ballots move depends on their order: ballots are taken
 * in the order given (a BLT line with a count of k is k consecutive identical ballots), as
 * Cambridge numbers them in the order they're counted.
 *
 * <ul>
 * <li>The quota is fixed for the whole count: the valid ballots divided by seats + 1, rounded
 * down, plus 1 (a whole-vote Droop quota).
 * <li>Every ballot first goes to its first choice; a candidate reaching the quota is elected
 * once that first count is done (Cambridge's own result pages: "elected immediately (after the
 * 1st count)"). Ballots are kept in each candidate's pile in the order received.
 * <li>While any elected candidate holds more than the quota, the largest such surplus is
 * transferred, Cincinnati-style: with n = total / surplus rounded to the nearest whole number,
 * the candidate's n-th, 2n-th, 3n-th... ballots are taken in turn, then the (n+1)-th,
 * (2n+1)-th..., and so on, then the first n-1 (a surplus of exactly 1 takes the last ballot
 * first). Each taken ballot moves whole to its next continuing choice; a ballot without one is
 * skipped and stays put. A candidate reaching the quota mid-transfer receives no more, and the
 * transfer stops once the whole surplus has moved. The transferring candidate's total is then
 * fixed at the quota -- whatever they still hold above it (ballots that couldn't move) counts
 * as non-transferable.
 * <li>Otherwise candidates are excluded: the first time, every continuing candidate under 50
 * votes at once ({@link #BATCH_CUTOFF}); after that (or if nobody is under 50), the one with
 * fewest votes. An excluded candidate's ballots move whole, in the order they were received,
 * to their next continuing choice -- again closing to candidates who reach the quota -- the
 * excluded candidates' piles going lowest first.
 * <li>The count ends once every seat is filled, or once the candidates left can only just
 * fill the seats left: they're elected as they stand (any on zero votes aren't, and if one too
 * many remain, the lowest goes).
 * </ul>
 *
 * <p>A tie for most or fewest votes is broken by looking back: at each earlier round in turn,
 * most recent first, only the tied candidates who were extreme then stay tied. If that never
 * leaves one, {@link CambridgeRuleset#tieBreak()} decides.
 *
 * <p>Results come back as an {@link StvResult}, so every STV report format works: an exclusion
 * is an {@code Eliminated} round, and a surplus transfer an {@code Elected} round carrying the
 * surplus transferred, recorded when the transfer happens. A candidate elected exactly on the
 * quota has nothing to transfer and is recorded as soon as they're elected; one whose surplus
 * the count ended before transferring is recorded at the end with none. {@link
 * StvResult#electedInOrder()} is the order candidates were elected in. Each round's tallies are
 * those the round's decision was made on: the continuing candidates, plus the candidate acted
 * on.
 *
 * <p>Like OpenSTV, this refuses an election with fewer than {@link #BATCH_CUTOFF} ballots per
 * seat, where the 50-vote first exclusion couldn't sensibly apply.
 */
public final class CambridgeCounter {

    /** Candidates under this many votes are all excluded at once, the first time anyone is. */
    public static final int BATCH_CUTOFF = 50;

    private CambridgeCounter() {
    }

    public static StvResult count(List<Candidate> candidates, List<Ballot> rawBallots, int seats,
            CambridgeRuleset ruleset) {
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive, got: " + seats);
        }
        if (seats > candidates.size()) {
            throw new IllegalArgumentException(
                    "cannot fill " + seats + " seat(s) with only " + candidates.size() + " candidate(s)");
        }
        ruleset.tiedRankings().requireNotSplit("Cambridge STV");
        BallotFormality.FormalBallots formal = BallotFormality.apply(
                rawBallots, ruleset.tiedRankings(), ruleset.minimumPreferences(), candidates.size());
        return new Count(candidates, formal, seats, ruleset).run();
    }

    /**
     * The order in which the Cincinnati method considers a candidate's {@code total} ballots
     * (0-based positions in their pile) to transfer a {@code surplus}: every n-th from the n-th,
     * then every n-th from the (n+1)-th, and so on, then the first n-1, where n is {@code total /
     * surplus} rounded to the nearest whole number (halves up). A surplus of 1 takes the last
     * ballot first, then the rest from the start.
     */
    static int[] transferOrder(int total, int surplus) {
        int[] order = new int[total];
        int k = 0;
        if (surplus == 1) {
            order[k++] = total - 1;
            for (int i = 0; i < total - 1; i++) {
                order[k++] = i;
            }
            return order;
        }
        int skip = (int) ((2L * total + surplus) / (2L * surplus));
        int start = skip - 1;
        for (int i = start; i < start + skip; i++) {
            for (int j = i; j < total; j += skip) {
                order[k++] = j;
            }
        }
        for (int i = 0; i < start; i++) {
            order[k++] = i;
        }
        return order;
    }

    private static final class Count {
        private final List<Candidate> candidates;
        private final BallotFormality.FormalBallots formal;
        private final int seats;
        private final CambridgeRuleset ruleset;
        private final int n;
        // Every individual ballot, in order, as an index into its distinct ranking.
        private final int[][] rankings;
        private final int[] rankingOf;
        private final int ballotCount;
        private final int quota;
        private final IntList[] piles;
        private final boolean[] continuing;
        private final boolean[] electedOver;   // elected, surplus not yet transferred
        private final boolean[] electedFixed;  // elected, surplus transferred: counts as the quota
        private final List<int[]> counts = new ArrayList<>();
        private final List<Integer> electedOrder = new ArrayList<>();
        private final List<RoundRecord> rounds = new ArrayList<>();
        private boolean firstExclusion = true;

        Count(List<Candidate> candidates, BallotFormality.FormalBallots formal, int seats, CambridgeRuleset ruleset) {
            this.candidates = candidates;
            this.formal = formal;
            this.seats = seats;
            this.ruleset = ruleset;
            this.n = candidates.size();
            Map<Candidate, Integer> indexOf = new LinkedHashMap<>();
            for (int c = 0; c < n; c++) {
                indexOf.put(candidates.get(c), c);
            }
            List<int[]> distinct = new ArrayList<>();
            List<Long> counts = new ArrayList<>();
            long total = 0;
            for (Ballot ballot : formal.ballots()) {
                // Ballots here never hold a tie (split is refused; the other policies remove ties).
                // A candidate this count doesn't know about can never receive a vote: dropped.
                distinct.add(ballot.ranking().stream().map(rank -> indexOf.get(rank.iterator().next()))
                        .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).toArray());
                counts.add(ballot.count().longValueExact());
                total += ballot.count().longValueExact();
            }
            if (total > Integer.MAX_VALUE / 2) {
                throw new IllegalArgumentException(total + " ballots is too many to count one by one");
            }
            if (total < (long) BATCH_CUTOFF * seats) {
                throw new IllegalArgumentException("Cambridge STV needs at least " + BATCH_CUTOFF + " ballots per seat ("
                        + BATCH_CUTOFF * seats + " here) but there are only " + total);
            }
            this.rankings = distinct.toArray(int[][]::new);
            this.ballotCount = (int) total;
            this.rankingOf = new int[ballotCount];
            int b = 0;
            for (int r = 0; r < rankings.length; r++) {
                for (long copy = 0; copy < counts.get(r); copy++) {
                    rankingOf[b++] = r;
                }
            }
            this.quota = ballotCount / (seats + 1) + 1;
            this.piles = new IntList[n];
            for (int c = 0; c < n; c++) {
                piles[c] = new IntList();
            }
            this.continuing = new boolean[n];
            Arrays.fill(continuing, true);
            this.electedOver = new boolean[n];
            this.electedFixed = new boolean[n];
        }

        StvResult run() {
            boolean[] everyone = continuing.clone();
            for (int b = 0; b < ballotCount; b++) {
                int c = topChoice(b, everyone);
                if (c >= 0) {
                    piles[c].add(b);
                }
            }
            update();

            while (!over()) {
                int previous = counts.size() - 1;
                if (surplusAt(previous) > 0) {
                    transferSurplus(previous);
                } else {
                    exclude(previous);
                }
                update();
            }
            finish();

            List<Candidate> elected = electedOrder.stream().map(candidates::get).toList();
            return new StvResult(elected, rounds, Fraction.of(quota), formal.informal());
        }

        /** OpenSTV's updateRound: record the counts, then elect whoever has reached the quota. */
        private void update() {
            int[] count = new int[n];
            for (int c = 0; c < n; c++) {
                count[c] = electedFixed[c] ? quota : piles[c].size();
            }
            counts.add(count);
            int round = counts.size() - 1;
            for (int c = 0; c < n; c++) {
                if (continuing[c] && count[c] >= quota) {
                    continuing[c] = false;
                    electedOver[c] = true;
                    electedOrder.add(c);
                    if (count[c] == quota) {
                        // Nothing to transfer: recorded as elected straight away.
                        record(round, c, new RoundAction.Elected(candidates.get(c), Fraction.ZERO));
                    }
                }
            }
        }

        private boolean over() {
            int elected = electedOrder.size();
            return elected == seats || continuingCount() + elected <= seats;
        }

        private long surplusAt(int round) {
            long surplus = 0;
            for (int c = 0; c < n; c++) {
                if ((electedOver[c] || continuing[c]) && counts.get(round)[c] > quota) {
                    surplus += counts.get(round)[c] - quota;
                }
            }
            return surplus;
        }

        private void transferSurplus(int round) {
            List<Integer> withSurplus = new ArrayList<>();
            for (int c = 0; c < n; c++) {
                if (electedOver[c]) {
                    withSurplus.add(c);
                }
            }
            int from = breakTie(withSurplus, true, round);
            int total = counts.get(round)[from];
            int surplus = total - quota;
            record(round, from, new RoundAction.Elected(candidates.get(from), Fraction.of(surplus)));
            electedOver[from] = false;
            electedFixed[from] = true;

            boolean[] open = continuing.clone();
            int[] pile = piles[from].toArray();
            boolean[] moved = new boolean[total];
            int transferred = 0;
            for (int position : transferOrder(total, surplus)) {
                int to = topChoice(pile[position], open);
                if (to >= 0) {
                    give(pile[position], to, open);
                    moved[position] = true;
                    transferred++;
                }
                if (transferred == surplus) {
                    break;
                }
            }
            IntList kept = new IntList();
            for (int position = 0; position < total; position++) {
                if (!moved[position]) {
                    kept.add(pile[position]);
                }
            }
            piles[from] = kept;
        }

        private void exclude(int round) {
            List<Integer> excluded = new ArrayList<>();
            if (firstExclusion) {
                for (int c = 0; c < n; c++) {
                    if (continuing[c] && counts.get(round)[c] < BATCH_CUTOFF) {
                        excluded.add(c);
                    }
                }
            }
            firstExclusion = false;
            if (excluded.isEmpty()) {
                List<Integer> standing = new ArrayList<>();
                for (int c = 0; c < n; c++) {
                    if (continuing[c]) {
                        standing.add(c);
                    }
                }
                excluded.add(breakTie(standing, false, round));
            }

            List<Integer> order = new ArrayList<>();
            List<Integer> remaining = new ArrayList<>();
            for (int c : excluded) {
                (counts.get(round)[c] == 0 ? order : remaining).add(c);
            }
            while (!remaining.isEmpty()) {
                int next = breakTie(remaining, false, round);
                remaining.remove(Integer.valueOf(next));
                order.add(next);
            }
            // Every exclusion in a batch was decided on the same tallies.
            for (int c : order) {
                record(round, c, new RoundAction.Eliminated(candidates.get(c)));
            }
            // The excluded go first, so none of their ballots can move to another of them.
            excluded.forEach(c -> continuing[c] = false);
            boolean[] open = continuing.clone();
            for (int c : order) {
                int[] pile = piles[c].toArray();
                for (int ballot : pile) {
                    int to = topChoice(ballot, open);
                    if (to >= 0) {
                        give(ballot, to, open);
                    }
                }
                piles[c] = new IntList();
            }
        }

        /** OpenSTV's updateCandidateStatus: settle whoever is still continuing once the count stops. */
        private void finish() {
            int round = counts.size() - 1;
            // Elected, but the count ended before their surplus was transferred.
            for (int c = 0; c < n; c++) {
                if (electedOver[c] && counts.get(round)[c] > quota) {
                    record(round, c, new RoundAction.Elected(candidates.get(c), Fraction.ZERO));
                }
            }
            if (electedOrder.size() == seats) {
                return;
            }
            for (int c = 0; c < n; c++) {
                if (continuing[c] && counts.get(round)[c] == 0) {
                    continuing[c] = false;
                }
            }
            if (continuingCount() + electedOrder.size() > seats) {
                List<Integer> standing = new ArrayList<>();
                for (int c = 0; c < n; c++) {
                    if (continuing[c]) {
                        standing.add(c);
                    }
                }
                int lowest = breakTie(standing, false, round);
                record(round, lowest, new RoundAction.Eliminated(candidates.get(lowest)));
                continuing[lowest] = false;
            }
            List<Integer> remaining = new ArrayList<>();
            for (int c = 0; c < n; c++) {
                if (continuing[c]) {
                    remaining.add(c);
                }
            }
            for (int c : remaining) {
                record(round, c, new RoundAction.Elected(candidates.get(c), Fraction.ZERO));
                electedOrder.add(c);
            }
            for (int c : remaining) {
                continuing[c] = false;
            }
        }

        /** Moves ballot {@code b} to candidate {@code to}, closing them once they reach the quota. */
        private void give(int b, int to, boolean[] open) {
            piles[to].add(b);
            if (piles[to].size() >= quota) {
                open[to] = false;
            }
        }

        private int topChoice(int b, boolean[] among) {
            for (int c : rankings[rankingOf[b]]) {
                if (among[c]) {
                    return c;
                }
            }
            return -1;
        }

        /**
         * The candidate with most (or fewest) votes at {@code round}, a tie narrowed by looking
         * back through earlier rounds, most recent first, then settled by the ruleset's tie-break.
         */
        private int breakTie(List<Integer> among, boolean most, int round) {
            List<Integer> tied = extreme(among, most, counts.get(round));
            for (int r = round - 1; r >= 0 && tied.size() > 1; r--) {
                tied = extreme(tied, most, counts.get(r));
            }
            if (tied.size() == 1) {
                return tied.getFirst();
            }
            List<Candidate> tiedCandidates = tied.stream().map(candidates::get).toList();
            Candidate chosen = most
                    ? ruleset.tieBreak().candidateToElectFirst(tiedCandidates, List.copyOf(rounds))
                    : ruleset.tieBreak().candidateToEliminate(tiedCandidates, List.copyOf(rounds));
            return candidates.indexOf(chosen);
        }

        private static List<Integer> extreme(List<Integer> among, boolean most, int[] count) {
            int best = most ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            for (int c : among) {
                best = most ? Math.max(best, count[c]) : Math.min(best, count[c]);
            }
            List<Integer> result = new ArrayList<>();
            for (int c : among) {
                if (count[c] == best) {
                    result.add(c);
                }
            }
            return result;
        }

        private void record(int round, int c, RoundAction action) {
            rounds.add(snapshot(round, c, action));
        }

        /** The tallies a decision at {@code round} rests on: every continuing candidate, plus {@code c}. */
        private RoundRecord snapshot(int round, int c, RoundAction action) {
            int[] count = counts.get(round);
            Map<Candidate, Fraction> tallies = new LinkedHashMap<>();
            long held = 0;
            for (int k = 0; k < n; k++) {
                held += count[k];
                if (continuing[k] || k == c) {
                    tallies.put(candidates.get(k), Fraction.of(count[k]));
                }
            }
            return new RoundRecord(rounds.size() + 1, tallies, action, Fraction.of(BigInteger.valueOf(ballotCount - held)));
        }

        private int continuingCount() {
            int count = 0;
            for (boolean c : continuing) {
                if (c) {
                    count++;
                }
            }
            return count;
        }
    }

    /** A growable list of ints, for the piles of ballot numbers. */
    private static final class IntList {
        private int[] values = new int[16];
        private int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        int size() {
            return size;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
