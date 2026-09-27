package info.fkhodkov.rankedchoice.cpostv;

import java.math.BigInteger;
import java.util.ArrayList;
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
 * scanning every ballot, so this is only practical for a small candidate/seat count. {@link
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

        Map<Set<Candidate>, Map<Set<Candidate>, Fraction>> scores = new LinkedHashMap<>();
        for (Set<Candidate> outcome : outcomes) {
            scores.put(outcome, new LinkedHashMap<>());
        }
        for (int i = 0; i < outcomes.size(); i++) {
            for (int j = i + 1; j < outcomes.size(); j++) {
                Set<Candidate> a = outcomes.get(i);
                Set<Candidate> b = outcomes.get(j);
                Map<Candidate, Fraction> tallies = notionalCount(ballots, a, b, quota, ruleset);
                Fraction scoreA = sumOf(tallies, a);
                Fraction scoreB = sumOf(tallies, b);
                scores.get(a).put(b, scoreA);
                scores.get(b).put(a, scoreB);
            }
        }

        List<Set<Set<Candidate>>> ranking = rank(outcomes, scores);
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
     * Scores outcomes {@code a} and {@code b} against each other in one pass: excludes every
     * candidate in neither, then repeatedly transfers the largest still-pending surplus among
     * {@code a} &cap; {@code b} until none remain (see the class javadoc).
     */
    private static Map<Candidate, Fraction> notionalCount(
            List<Ballot> ballots, Set<Candidate> a, Set<Candidate> b, Fraction quota, CpoStvRuleset ruleset) {
        Set<Candidate> standing = new LinkedHashSet<>(a);
        standing.addAll(b);
        Set<Candidate> shared = new LinkedHashSet<>(a);
        shared.retainAll(b);

        List<BallotState> states = new ArrayList<>(ballots.size());
        for (Ballot ballot : ballots) {
            if (ballot.isBlank()) {
                continue;
            }
            BallotState state = new BallotState(ballot);
            assignNextHolder(state, standing);
            states.add(state);
        }

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
            Map<Candidate, Fraction> tallies = computeTallies(states, standing, ruleset.roundingRule());
            Candidate largestSurplusCandidate = null;
            Fraction largestSurplus = Fraction.ZERO;
            for (Candidate candidate : shared) {
                if (frozenAtQuota.containsKey(candidate)) {
                    continue;
                }
                Fraction surplus = tallies.get(candidate).subtract(quota);
                if (surplus.signum() > 0 && surplus.compareTo(largestSurplus) >= 0) {
                    largestSurplus = surplus;
                    largestSurplusCandidate = candidate;
                }
            }
            if (largestSurplusCandidate == null) {
                tallies.putAll(frozenAtQuota);
                return tallies;
            }
            frozenAtQuota.put(largestSurplusCandidate, quota);
            transferSurplus(states, largestSurplusCandidate, largestSurplus, ruleset.transferMethod(),
                    ruleset.roundingRule(), standing, nextParcelId++);
        }
    }

    private static final class BallotState {
        private final Ballot ballot;
        private int nextIndex = 0;
        private Fraction value = Fraction.ONE;
        private int parcelId = 0;
        private Candidate holder;

        BallotState(Ballot ballot) {
            this.ballot = ballot;
        }
    }

    private static void assignNextHolder(BallotState state, Set<Candidate> standing) {
        List<Set<Candidate>> ranking = state.ballot.ranking();
        while (state.nextIndex < ranking.size()) {
            Candidate candidate = ranking.get(state.nextIndex).iterator().next();
            state.nextIndex++;
            if (standing.contains(candidate)) {
                state.holder = candidate;
                return;
            }
        }
        state.holder = null;
    }

    private static Map<Candidate, Fraction> computeTallies(
            List<BallotState> states, Set<Candidate> standing, RoundingRule roundingRule) {
        Map<Candidate, Fraction> tallies = new LinkedHashMap<>();
        for (Candidate candidate : standing) {
            tallies.put(candidate, Fraction.ZERO);
        }
        for (BallotState state : states) {
            if (state.holder != null) {
                tallies.merge(state.holder, Fraction.of(state.ballot.count()).multiply(state.value), Fraction::add);
            }
        }
        tallies.replaceAll((_, tally) -> roundingRule.apply(tally));
        return tallies;
    }

    private record ParcelKey(int parcelId, Fraction value, boolean hasNextPreference) {
    }

    private static void transferSurplus(List<BallotState> states, Candidate from, Fraction surplus,
            TransferMethod transferMethod, RoundingRule roundingRule, Set<Candidate> standing, int newParcelId) {
        Map<ParcelKey, BigInteger> votesByKey = new LinkedHashMap<>();
        for (BallotState state : states) {
            if (from.equals(state.holder)) {
                boolean hasNextPreference = hasNextStandingPreference(state, standing);
                votesByKey.merge(new ParcelKey(state.parcelId, state.value, hasNextPreference),
                        state.ballot.count(), BigInteger::add);
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

        for (BallotState state : states) {
            if (from.equals(state.holder)) {
                boolean hasNextPreference = hasNextStandingPreference(state, standing);
                Integer id = idByKey.get(new ParcelKey(state.parcelId, state.value, hasNextPreference));
                Fraction newValue = newValues.get(id);
                if (newValue != null) {
                    state.value = newValue;
                    state.parcelId = newParcelId;
                    assignNextHolder(state, standing);
                }
            }
        }
    }

    private static boolean hasNextStandingPreference(BallotState state, Set<Candidate> standing) {
        List<Set<Candidate>> ranking = state.ballot.ranking();
        for (int i = state.nextIndex; i < ranking.size(); i++) {
            if (standing.contains(ranking.get(i).iterator().next())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The same beatpath-completion idea as {@link
     * info.fkhodkov.rankedchoice.schulze.SchulzeMethod}, applied to outcomes instead of
     * candidates and {@link Fraction}-valued scores instead of {@link BigInteger} vote counts.
     */
    private static List<Set<Set<Candidate>>> rank(
            List<Set<Candidate>> outcomes, Map<Set<Candidate>, Map<Set<Candidate>, Fraction>> d) {
        Map<Set<Candidate>, Map<Set<Candidate>, Fraction>> p = new LinkedHashMap<>();
        for (Set<Candidate> i : outcomes) {
            Map<Set<Candidate>, Fraction> row = new LinkedHashMap<>();
            for (Set<Candidate> j : outcomes) {
                if (i.equals(j)) {
                    continue;
                }
                Fraction dij = d.get(i).get(j);
                Fraction dji = d.get(j).get(i);
                row.put(j, dij.compareTo(dji) > 0 ? dij : Fraction.ZERO);
            }
            p.put(i, row);
        }

        for (Set<Candidate> k : outcomes) {
            for (Set<Candidate> i : outcomes) {
                if (i.equals(k)) {
                    continue;
                }
                for (Set<Candidate> j : outcomes) {
                    if (j.equals(i) || j.equals(k)) {
                        continue;
                    }
                    Fraction throughK = p.get(i).get(k);
                    Fraction fromK = p.get(k).get(j);
                    Fraction viaK = throughK.compareTo(fromK) <= 0 ? throughK : fromK;
                    if (viaK.compareTo(p.get(i).get(j)) > 0) {
                        p.get(i).put(j, viaK);
                    }
                }
            }
        }

        Map<Set<Candidate>, Integer> wins = new LinkedHashMap<>();
        for (Set<Candidate> i : outcomes) {
            int count = 0;
            for (Set<Candidate> j : outcomes) {
                if (!i.equals(j) && p.get(i).get(j).compareTo(p.get(j).get(i)) > 0) {
                    count++;
                }
            }
            wins.put(i, count);
        }

        Map<Integer, Set<Set<Candidate>>> groups = new TreeMap<>(java.util.Comparator.reverseOrder());
        for (Set<Candidate> outcome : outcomes) {
            groups.computeIfAbsent(wins.get(outcome), _ -> new LinkedHashSet<>()).add(outcome);
        }
        return new ArrayList<>(groups.values());
    }
}
