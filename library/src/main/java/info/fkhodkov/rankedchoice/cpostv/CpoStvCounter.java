package info.fkhodkov.rankedchoice.cpostv;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.BallotFormality;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.transfer.HeldParcel;
import info.fkhodkov.rankedchoice.stv.transfer.TransferMethod;

/**
 * Runs a count by CPO-STV (Comparison of Pairs of Outcomes by the Single Transferable Vote,
 * Nicolaus Tideman) — a fundamentally different shape from every other method in this project:
 * instead of running one count and watching a single sequence of elections/eliminations play
 * out, it exhaustively enumerates every possible {@code seats}-sized "outcome" (candidate
 * subset), scores every pair of them against each other via a small notional STV-style count,
 * and picks the outcome that wins every such pairwise comparison — falling back to a beatpath
 * completion (the same idea {@link info.fkhodkov.rankedchoice.schulze.SchulzeMethod} uses, but
 * over {@link Fraction}-valued scores here rather than {@link BigInteger} vote counts, so it's
 * reimplemented locally rather than reusing that class directly) if no single outcome beats
 * every other outright.
 *
 * <p>Comparing outcome {@code A} against outcome {@code B}: every candidate in neither {@code
 * A} nor {@code B} is excluded up front (their ballots move to their next preference among
 * {@code A} &cup; {@code B}, exactly like an STV elimination). Then, only candidates shared by
 * <em>both</em> outcomes ({@code A} &cap; {@code B}) ever have their surplus transferred — a
 * candidate unique to just one outcome keeps whatever they end up with, however far above or
 * below quota, since the question being asked is precisely how much strength each outcome's
 * own exclusive candidates retain. Once every shared candidate's surplus (if any) has been
 * transferred, each outcome's score is the sum of its own members' final tallies; the higher
 * score wins that comparison. Verified against the CPO-STV Wikipedia article's own worked
 * example (5 candidates, 3 seats) — see {@link CpoStvCounterTest}.
 *
 * <p>Reuses this project's existing {@link info.fkhodkov.rankedchoice.stv.quota.Quota} and
 * {@link TransferMethod} abstractions for the notional count (any quota or transfer method
 * already implemented for classic STV works here too), but doesn't need a {@code
 * TieBreakRule} — see {@link CpoStvRuleset}'s javadoc.
 *
 * <p>The number of outcomes is {@code C(candidates, seats)}, and the number of pairwise
 * comparisons is quadratic in that — {@code C(outcomes, 2)} — with each comparison itself
 * scanning every distinct ranking (though then counting only the few hundred orderings of its
 * own candidates those reduce to, and comparisons run in parallel), so this is only practical
 * for a small candidate/seat count. {@link
 * #MAX_OUTCOMES} enforces a cap (500 outcomes, i.e. at most 124,750 comparisons) rather than
 * letting a larger election silently hang; there's no real-world precedent to size this
 * against, since CPO-STV sees virtually no practical use for exactly this reason.
 */
public final class CpoStvCounter {

    /** See the class javadoc's note on combinatorial cost. */
    public static final int MAX_OUTCOMES = 500;

    private CpoStvCounter() {
    }

    public static CpoStvResult count(
            List<Candidate> candidates, List<Ballot> rawBallots, int seats, CpoStvRuleset ruleset) {
        validate(candidates, seats);
        ruleset.tiedRankings().requireNotSplit("CPO-STV");
        BallotFormality.FormalBallots formal = BallotFormality.apply(
                rawBallots, ruleset.tiedRankings(), ruleset.minimumPreferences(), candidates.size());
        List<Ballot> ballots = formal.ballots();

        // Checked via the closed-form binomial coefficient, not by enumerating first: for a
        // large candidates/seats combination, actually building the outcome list would itself
        // be the runaway computation this cap exists to prevent.
        BigInteger outcomeCount = binomialCoefficient(candidates.size(), seats);
        if (outcomeCount.compareTo(BigInteger.valueOf(MAX_OUTCOMES)) > 0) {
            throw new IllegalArgumentException("C(" + candidates.size() + ", " + seats + ") = " + outcomeCount
                    + " possible outcomes exceeds this implementation's cap of " + MAX_OUTCOMES
                    + " -- CPO-STV's pairwise comparisons scale quadratically in outcome count, so this "
                    + "candidate/seat combination isn't practical to run exhaustively");
        }
        List<Set<Candidate>> outcomes = enumerateOutcomes(candidates, seats);

        BigInteger totalValidVotes = BigInteger.ZERO;
        for (Ballot ballot : ballots) {
            if (!ballot.isBlank()) {
                totalValidVotes = totalValidVotes.add(ballot.count());
            }
        }
        Fraction quota = ruleset.quota().compute(totalValidVotes, seats);

        if (outcomes.size() == 1) {
            Set<Candidate> only = outcomes.getFirst();
            return new CpoStvResult(Map.of(), List.of(Set.of(only)), formal.informal());
        }

        IndexedBallots indexed = IndexedBallots.of(candidates, ballots);
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            for (int j = i + 1; j < outcomes.size(); j++) {
                pairs.add(new int[] {i, j});
            }
        }
        // Pairs are independent and every figure is exact, so counting them in parallel gives
        // the same scores in any order; they're collected back in pair order below.
        List<Fraction[]> pairScores = pairs.parallelStream().map(pair -> {
            Set<Candidate> a = outcomes.get(pair[0]);
            Set<Candidate> b = outcomes.get(pair[1]);
            Map<Candidate, Fraction> tallies = notionalCount(indexed, a, b, quota, ruleset);
            return new Fraction[] {sumOf(tallies, a), sumOf(tallies, b)};
        }).toList();

        // d[i][j]: outcome i's score against outcome j.
        Fraction[][] d = new Fraction[outcomes.size()][outcomes.size()];
        for (int p = 0; p < pairs.size(); p++) {
            int i = pairs.get(p)[0];
            int j = pairs.get(p)[1];
            d[i][j] = pairScores.get(p)[0];
            d[j][i] = pairScores.get(p)[1];
        }
        Map<Set<Candidate>, Map<Set<Candidate>, Fraction>> scores = new LinkedHashMap<>();
        for (int i = 0; i < outcomes.size(); i++) {
            Map<Set<Candidate>, Fraction> row = new LinkedHashMap<>();
            for (int j = 0; j < outcomes.size(); j++) {
                if (i != j) {
                    row.put(outcomes.get(j), d[i][j]);
                }
            }
            scores.put(outcomes.get(i), row);
        }

        List<Set<Set<Candidate>>> ranking = rank(outcomes, d);
        return new CpoStvResult(scores, ranking, formal.informal());
    }

    private static void validate(List<Candidate> candidates, int seats) {
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive, got: " + seats);
        }
        if (seats > candidates.size()) {
            throw new IllegalArgumentException(
                    "cannot fill " + seats + " seat(s) with only " + candidates.size() + " candidate(s)");
        }
    }

    /** {@code n! / (k! * (n-k)!)}, computed incrementally so it never builds either factorial directly. */
    private static BigInteger binomialCoefficient(int n, int k) {
        if (k > n - k) {
            return binomialCoefficient(n, n - k);
        }
        BigInteger result = BigInteger.ONE;
        for (int i = 0; i < k; i++) {
            result = result.multiply(BigInteger.valueOf(n - i)).divide(BigInteger.valueOf(i + 1));
        }
        return result;
    }

    private static List<Set<Candidate>> enumerateOutcomes(List<Candidate> candidates, int seats) {
        List<Set<Candidate>> outcomes = new ArrayList<>();
        combinations(candidates, seats, 0, new ArrayList<>(), outcomes);
        return outcomes;
    }

    private static void combinations(List<Candidate> candidates, int seats, int start,
            List<Candidate> current, List<Set<Candidate>> outcomes) {
        if (current.size() == seats) {
            outcomes.add(Set.copyOf(current));
            return;
        }
        for (int i = start; i < candidates.size(); i++) {
            current.add(candidates.get(i));
            combinations(candidates, seats, i + 1, current, outcomes);
            current.remove(current.size() - 1);
        }
    }

    private static Fraction sumOf(Map<Candidate, Fraction> tallies, Set<Candidate> outcome) {
        Fraction sum = Fraction.ZERO;
        for (Candidate candidate : outcome) {
            sum = sum.add(tallies.get(candidate));
        }
        return sum;
    }

    /**
     * Every distinct ranking, once, as candidate indices, with how many ballots cast it — the
     * form {@link #notionalCount} projects from, many times over, so it's built only once.
     */
    private record IndexedBallots(List<Candidate> candidates, Map<Candidate, Integer> indexOf,
            List<int[]> rankings, List<BigInteger> counts) {

        static IndexedBallots of(List<Candidate> candidates, List<Ballot> ballots) {
            Map<Candidate, Integer> indexOf = new LinkedHashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                indexOf.put(candidates.get(i), i);
            }
            Map<RankingKey, Integer> seen = new LinkedHashMap<>();
            List<int[]> rankings = new ArrayList<>();
            List<BigInteger> counts = new ArrayList<>();
            for (Ballot ballot : ballots) {
                if (ballot.isBlank()) {
                    continue;
                }
                // Ballots here never hold a tie (split is refused, other policies remove ties).
                int[] ranking = ballot.ranking().stream()
                        .mapToInt(rank -> indexOf.get(rank.iterator().next()))
                        .toArray();
                Integer at = seen.putIfAbsent(new RankingKey(ranking), rankings.size());
                if (at == null) {
                    rankings.add(ranking);
                    counts.add(ballot.count());
                } else {
                    counts.set(at, counts.get(at).add(ballot.count()));
                }
            }
            return new IndexedBallots(candidates, indexOf, rankings, counts);
        }
    }

    /** An {@code int[]} ranking with value equality, for merging identical rankings. */
    private record RankingKey(int[] ranking) {
        @Override
        public boolean equals(Object other) {
            return other instanceof RankingKey(int[] otherRanking) && Arrays.equals(ranking, otherRanking);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(ranking);
        }
    }

    /**
     * Scores outcomes {@code a} and {@code b} against each other in one pass: excludes every
     * candidate in neither, then repeatedly transfers the largest still-pending surplus among
     * {@code a} &cap; {@code b} until none remain (see the class javadoc). When two are equal,
     * the one belonging to the candidate declared first goes first.
     *
     * <p>Only the order in which a ballot ranks the candidates of {@code a} &cup; {@code b} can
     * affect this count — every other candidate is excluded before it starts — so ballots are
     * first reduced to that order and identical ones merged. Two such ballots sit with the same
     * candidate, in the same parcel, at the same value throughout, and every tally is an exact
     * sum, so the merged count is identical to counting them one by one — just over a few
     * hundred distinct ballots rather than every ballot cast.
     */
    private static Map<Candidate, Fraction> notionalCount(
            IndexedBallots ballots, Set<Candidate> a, Set<Candidate> b, Fraction quota, CpoStvRuleset ruleset) {
        // In declared candidate order, not the outcome sets' own iteration order, which varies
        // from run to run: `shared` decides which of two equal surpluses transfers first.
        Set<Candidate> standing = new LinkedHashSet<>();
        Set<Candidate> shared = new LinkedHashSet<>();
        for (Candidate candidate : ballots.candidates()) {
            if (a.contains(candidate) || b.contains(candidate)) {
                standing.add(candidate);
            }
            if (a.contains(candidate) && b.contains(candidate)) {
                shared.add(candidate);
            }
        }

        boolean[] isStanding = new boolean[ballots.candidates().size()];
        for (Candidate candidate : standing) {
            isStanding[ballots.indexOf().get(candidate)] = true;
        }
        Map<RankingKey, Integer> seen = new LinkedHashMap<>();
        List<int[]> projections = new ArrayList<>();
        List<BigInteger> counts = new ArrayList<>();
        int[] buffer = new int[standing.size()];
        for (int i = 0; i < ballots.rankings().size(); i++) {
            int length = 0;
            for (int candidate : ballots.rankings().get(i)) {
                if (isStanding[candidate]) {
                    buffer[length++] = candidate;
                }
            }
            if (length == 0) {
                continue; // ranks nobody standing: exhausted before this count starts, and stays so
            }
            int[] projection = Arrays.copyOf(buffer, length);
            Integer at = seen.putIfAbsent(new RankingKey(projection), projections.size());
            if (at == null) {
                projections.add(projection);
                counts.add(ballots.counts().get(i));
            } else {
                counts.set(at, counts.get(at).add(ballots.counts().get(i)));
            }
        }

        // Each merged ballot's state: the position of its current holder within its projection
        // (every projected candidate is standing, so the next holder is simply the next entry,
        // and a position past the end means exhausted), its current value, and the parcel it
        // arrived in.
        int count = projections.size();
        int[] position = new int[count];
        Fraction[] value = new Fraction[count];
        Arrays.fill(value, Fraction.ONE);
        int[] parcel = new int[count];

        // Once a shared candidate's surplus is transferred, every ballot they held moves on
        // (at a reduced value) to the next standing preference -- transferring "only the
        // surplus" is a matter of ratio, not of literally keeping some ballots back -- so their
        // own live tally would recompute to roughly zero afterward, same as a classic-STV
        // elected candidate's ballots all moving on. Their credit is frozen at exactly quota
        // (the whole point of transferring "the surplus above the quota") the moment their
        // transfer happens, and reported from here rather than ever recomputed from ballot
        // state again.
        Map<Candidate, Fraction> frozenAtQuota = new LinkedHashMap<>();
        int nextParcelId = 1;
        while (true) {
            Map<Candidate, Fraction> tallies =
                    computeTallies(ballots, standing, projections, counts, position, value, ruleset.roundingRule());
            Candidate largestSurplusCandidate = null;
            Fraction largestSurplus = Fraction.ZERO;
            for (Candidate candidate : shared) {
                if (frozenAtQuota.containsKey(candidate)) {
                    continue;
                }
                Fraction surplus = tallies.get(candidate).subtract(quota);
                // Strictly larger: of equal surpluses, the earliest-declared candidate's goes first.
                if (surplus.signum() > 0 && surplus.compareTo(largestSurplus) > 0) {
                    largestSurplus = surplus;
                    largestSurplusCandidate = candidate;
                }
            }
            if (largestSurplusCandidate == null) {
                tallies.putAll(frozenAtQuota);
                return tallies;
            }
            frozenAtQuota.put(largestSurplusCandidate, quota);
            transferSurplus(ballots.indexOf().get(largestSurplusCandidate), largestSurplus, projections, counts,
                    position, value, parcel, ruleset.transferMethod(), ruleset.roundingRule(), nextParcelId++);
        }
    }

    /**
     * Each standing candidate's tally, rounded by {@code roundingRule}: the ballots they hold,
     * summed exactly. Ballots held at the same value are added up as whole numbers first and
     * multiplied by that value once, which gives the same exact total as adding each ballot's
     * value separately.
     */
    private static Map<Candidate, Fraction> computeTallies(IndexedBallots ballots, Set<Candidate> standing,
            List<int[]> projections, List<BigInteger> counts, int[] position, Fraction[] value,
            RoundingRule roundingRule) {
        List<Map<Fraction, BigInteger>> heldByValue = new ArrayList<>(ballots.candidates().size());
        for (int c = 0; c < ballots.candidates().size(); c++) {
            heldByValue.add(null);
        }
        for (int i = 0; i < projections.size(); i++) {
            int[] projection = projections.get(i);
            if (position[i] < projection.length) {
                int holder = projection[position[i]];
                if (heldByValue.get(holder) == null) {
                    heldByValue.set(holder, new LinkedHashMap<>());
                }
                heldByValue.get(holder).merge(value[i], counts.get(i), BigInteger::add);
            }
        }
        Map<Candidate, Fraction> tallies = new LinkedHashMap<>();
        for (Candidate candidate : standing) {
            Fraction tally = Fraction.ZERO;
            Map<Fraction, BigInteger> held = heldByValue.get(ballots.indexOf().get(candidate));
            if (held != null) {
                for (Map.Entry<Fraction, BigInteger> entry : held.entrySet()) {
                    tally = tally.add(Fraction.of(entry.getValue()).multiply(entry.getKey()));
                }
            }
            tallies.put(candidate, roundingRule.apply(tally));
        }
        return tallies;
    }

    private record ParcelKey(int parcelId, Fraction value, boolean hasNextPreference) {
    }

    private static void transferSurplus(int from, Fraction surplus, List<int[]> projections, List<BigInteger> counts,
            int[] position, Fraction[] value, int[] parcel, TransferMethod transferMethod, RoundingRule roundingRule,
            int newParcelId) {
        Map<ParcelKey, BigInteger> votesByKey = new LinkedHashMap<>();
        for (int i = 0; i < projections.size(); i++) {
            if (isHeldBy(projections.get(i), position[i], from)) {
                votesByKey.merge(parcelKey(projections.get(i), position[i], parcel[i], value[i]),
                        counts.get(i), BigInteger::add);
            }
        }
        if (votesByKey.isEmpty()) {
            return;
        }

        Map<ParcelKey, Integer> idByKey = new LinkedHashMap<>();
        List<HeldParcel> parcels = new ArrayList<>(votesByKey.size());
        for (Map.Entry<ParcelKey, BigInteger> entry : votesByKey.entrySet()) {
            int id = idByKey.size();
            idByKey.put(entry.getKey(), id);
            parcels.add(new HeldParcel(id, entry.getKey().parcelId(), entry.getKey().value(), entry.getValue(),
                    entry.getKey().hasNextPreference()));
        }

        Map<Integer, Fraction> newValues = transferMethod.computeTransferValues(surplus, parcels, roundingRule);

        for (int i = 0; i < projections.size(); i++) {
            if (isHeldBy(projections.get(i), position[i], from)) {
                Fraction newValue = newValues.get(idByKey.get(parcelKey(projections.get(i), position[i], parcel[i],
                        value[i])));
                if (newValue != null) {
                    value[i] = newValue;
                    parcel[i] = newParcelId;
                    position[i]++;
                }
            }
        }
    }

    private static boolean isHeldBy(int[] projection, int position, int candidate) {
        return position < projection.length && projection[position] == candidate;
    }

    private static ParcelKey parcelKey(int[] projection, int position, int parcelId, Fraction value) {
        return new ParcelKey(parcelId, value, position + 1 < projection.length);
    }

    /**
     * The same beatpath-completion idea as {@link
     * info.fkhodkov.rankedchoice.schulze.SchulzeMethod}, applied to outcomes instead of
     * candidates and {@link Fraction}-valued scores instead of {@link BigInteger} vote counts.
     */
    private static List<Set<Set<Candidate>>> rank(List<Set<Candidate>> outcomes, Fraction[][] d) {
        int n = outcomes.size();
        Fraction[][] p = new Fraction[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    p[i][j] = d[i][j].compareTo(d[j][i]) > 0 ? d[i][j] : Fraction.ZERO;
                }
            }
        }

        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                if (i == k) {
                    continue;
                }
                Fraction throughK = p[i][k];
                for (int j = 0; j < n; j++) {
                    if (j == i || j == k) {
                        continue;
                    }
                    Fraction fromK = p[k][j];
                    Fraction viaK = throughK.compareTo(fromK) <= 0 ? throughK : fromK;
                    if (viaK.compareTo(p[i][j]) > 0) {
                        p[i][j] = viaK;
                    }
                }
            }
        }

        Map<Integer, Set<Set<Candidate>>> groups = new TreeMap<>(java.util.Comparator.reverseOrder());
        for (int i = 0; i < n; i++) {
            int wins = 0;
            for (int j = 0; j < n; j++) {
                if (i != j && p[i][j].compareTo(p[j][i]) > 0) {
                    wins++;
                }
            }
            groups.computeIfAbsent(wins, _ -> new LinkedHashSet<>()).add(outcomes.get(i));
        }
        return new ArrayList<>(groups.values());
    }
}
