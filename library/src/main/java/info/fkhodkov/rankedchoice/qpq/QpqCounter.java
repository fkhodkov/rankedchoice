package info.fkhodkov.rankedchoice.qpq;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.core.util.Interruption;
import info.fkhodkov.rankedchoice.stv.BallotFormality;
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;

/**
 * Runs a count by QPQ (Quota Preferential by Quotient, Douglas Woodall — "QPQ, a
 * quota-preferential STV-like election rule", <em>Voting matters</em> Issue 17, 2003) — a
 * fundamentally different shape from every parcel-based method in this project ({@link
 * info.fkhodkov.rankedchoice.stv.StvCounter}, {@link info.fkhodkov.rankedchoice.stv.meek.MeekCounter}):
 * a ballot's vote is never split or devalued. It always counts as one full vote for whichever
 * hopeful candidate currently tops its ranking; what changes instead is a per-ballot
 * bookkeeping number — how many candidates, fractionally, that ballot has "helped elect" so
 * far (its <em>elected-credit</em>) — used only to compute quotients and quota, never to scale
 * the ballot's own weight.
 *
 * <p>Each stage: every hopeful candidate {@code c}'s quotient is {@code qc = vc / (1 + tc)},
 * where {@code vc} is the number of ballots currently topping {@code c} and {@code tc} is
 * those same ballots' total elected-credit. The quota is {@code va / (1 + seats - tx)}, where
 * {@code va} is the total weight of all non-exhausted ballots and {@code tx} is the total
 * elected-credit banked by exhausted ballots. If the highest quotient exceeds the quota, that
 * candidate is elected: every ballot currently topping them has its elected-credit
 * <em>overwritten</em> (not added to) with {@code 1 / quotient} — regardless of whatever
 * credit it carried in before — and then advances to its next hopeful preference. Otherwise
 * the lowest-quotient candidate is permanently excluded, their ballots advance to their next
 * hopeful preference, and — this is the distinctive, easy-to-miss part of Woodall's rule —
 * <b>the whole count restarts</b>: every previously-elected candidate returns to hopeful,
 * every ballot's elected-credit resets to zero, and every ballot is re-walked from its very
 * first preference against the surviving candidates. Both reference implementations checked
 * against this one (droop's {@code qpq.py}, OpenSTV's {@code QPQ.py}) default this restart on
 * unconditionally; it isn't an optional embellishment, it's how the rule is meant to work.
 * This makes QPQ structurally closer to {@link
 * info.fkhodkov.rankedchoice.stv.meek.MeekCounter}'s whole-count iteration than to classic
 * STV's incremental parcel transfers — but simpler than Meek's method in one respect: since
 * this is exact quotient arithmetic rather than a converging keep-factor, there's no numeric
 * tolerance loop, just a bounded, finite sequence of exact-{@link Fraction} stages (see {@link
 * QpqRuleset}).
 *
 * <p>The very first stage is treated as though it were itself a post-exclusion restart (same
 * reset-and-rebuild logic, just with nothing excluded yet), unifying what would otherwise be
 * two separate code paths. Once as many candidates remain hopeful as there are seats left to
 * fill, they're all declared elected outright with no further stage — the same "remaining ==
 * remaining seats" shortcut classic STV uses, and exactly how Woodall's own rule (2.5b) and
 * both reference implementations handle it: no quotient computation for this final batch.
 */
public final class QpqCounter {

    private QpqCounter() {
    }

    public static QpqResult count(List<Candidate> candidates, List<Ballot> rawBallots, int seats, QpqRuleset ruleset) {
        validate(candidates, seats);
        ruleset.tiedRankings().requireNotSplit("QPQ");
        BallotFormality.FormalBallots formal = BallotFormality.apply(
                rawBallots, ruleset.tiedRankings(), ruleset.minimumPreferences(), candidates.size());
        List<Ballot> ballots = formal.ballots();

        BigInteger total = ballots.stream().map(Ballot::count).reduce(BigInteger.ZERO, BigInteger::add);
        if (total.bitLength() < Long.SIZE - 1) {
            return new IndexedCount(candidates, ballots, seats, ruleset.tieBreak()).run(formal);
        }
        return countInBigIntegers(candidates, ballots, seats, ruleset, formal);
    }

    /**
     * The count as {@link #count} describes it, over plain objects and {@link BigInteger} vote
     * counts -- used only when the total vote doesn't fit in a {@code long}, which {@link
     * IndexedCount} needs.
     */
    private static QpqResult countInBigIntegers(List<Candidate> candidates, List<Ballot> ballots, int seats,
            QpqRuleset ruleset, BallotFormality.FormalBallots formal) {
        List<BallotState> states = new ArrayList<>(ballots.size());
        for (Ballot ballot : ballots) {
            if (!ballot.isBlank()) {
                states.add(new BallotState(ballot));
            }
        }

        Set<Candidate> hopeful = new LinkedHashSet<>(candidates);
        // A LinkedHashSet, not a plain list: a restart returns every currently-elected
        // candidate to hopeful (see the class javadoc), and they can legitimately be elected
        // again afterward -- this must never grow into a duplicate entry, and its final
        // iteration order should reflect only the elections that survived the last restart,
        // not every election event across the whole count.
        Set<Candidate> elected = new LinkedHashSet<>();
        List<QpqRoundRecord> rounds = new ArrayList<>();
        int stageNumber = 0;
        boolean restartPending = true;

        while (elected.size() < seats && hopeful.size() > seats - elected.size()) {
            Interruption.check();
            stageNumber++;
            boolean restartedThisStage = restartPending;
            if (restartPending) {
                hopeful.addAll(elected);
                elected.clear();
                for (BallotState state : states) {
                    Interruption.check();
                    state.nextIndex = 0;
                    state.holder = null;
                    state.electedCredit = Fraction.ZERO;
                    assignNextHolder(state, hopeful);
                }
                restartPending = false;
            }

            Map<Candidate, BigInteger> vc = new LinkedHashMap<>();
            Map<Candidate, Fraction> tc = new LinkedHashMap<>();
            for (Candidate candidate : hopeful) {
                vc.put(candidate, BigInteger.ZERO);
                tc.put(candidate, Fraction.ZERO);
            }
            // Ballots are added up as whole numbers per (holder, credit) -- and per credit for
            // exhausted ones -- and each group multiplied by its credit once: the same exact sums
            // as weighting every ballot separately, without a fraction operation per ballot.
            BigInteger va = BigInteger.ZERO;
            Map<Candidate, Map<Fraction, BigInteger>> heldByCredit = new LinkedHashMap<>();
            Map<Fraction, BigInteger> exhaustedByCredit = new LinkedHashMap<>();
            for (BallotState state : states) {
                Interruption.check();
                if (state.holder == null) {
                    exhaustedByCredit.merge(state.electedCredit, state.ballot.count(), BigInteger::add);
                } else {
                    va = va.add(state.ballot.count());
                    vc.merge(state.holder, state.ballot.count(), BigInteger::add);
                    heldByCredit.computeIfAbsent(state.holder, _ -> new LinkedHashMap<>())
                            .merge(state.electedCredit, state.ballot.count(), BigInteger::add);
                }
            }
            for (Map.Entry<Candidate, Map<Fraction, BigInteger>> held : heldByCredit.entrySet()) {
                tc.put(held.getKey(), weightedTotal(held.getValue()));
            }
            Fraction tx = weightedTotal(exhaustedByCredit);

            Map<Candidate, Fraction> quotients = new LinkedHashMap<>();
            for (Candidate candidate : hopeful) {
                quotients.put(candidate, Fraction.of(vc.get(candidate)).divide(Fraction.ONE.add(tc.get(candidate))));
            }
            Fraction quota = Fraction.of(va).divide(Fraction.of(seats + 1L).subtract(tx));

            Candidate highest = pickExtreme(hopeful, quotients, true, ruleset.tieBreak());
            if (quotients.get(highest).compareTo(quota) > 0) {
                elected.add(highest);
                hopeful.remove(highest);
                Fraction newCredit = Fraction.ONE.divide(quotients.get(highest));
                for (BallotState state : states) {
                    Interruption.check();
                    if (highest.equals(state.holder)) {
                        state.electedCredit = newCredit;
                        assignNextHolder(state, hopeful);
                    }
                }
                rounds.add(new QpqRoundRecord(
                        stageNumber, quotients, quota, new QpqAction.Elected(highest), restartedThisStage));
            } else {
                Candidate lowest = pickExtreme(hopeful, quotients, false, ruleset.tieBreak());
                hopeful.remove(lowest);
                for (BallotState state : states) {
                    Interruption.check();
                    if (lowest.equals(state.holder)) {
                        assignNextHolder(state, hopeful);
                    }
                }
                rounds.add(new QpqRoundRecord(
                        stageNumber, quotients, quota, new QpqAction.Excluded(lowest), restartedThisStage));
                restartPending = true;
            }
        }

        // The loop above can also exit because every seat was already filled by an explicit
        // election -- only reaching it via the shortcut (hopeful == remaining seats) means
        // there's still room for every remaining hopeful.
        if (elected.size() < seats) {
            elected.addAll(hopeful);
        }

        return new QpqResult(List.copyOf(elected), rounds, formal.informal());
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

    private static Candidate pickExtreme(Set<Candidate> hopeful, Map<Candidate, Fraction> quotients,
            boolean highest, TieBreakRule tieBreak) {
        Fraction extreme = null;
        for (Candidate candidate : hopeful) {
            Fraction quotient = quotients.get(candidate);
            if (extreme == null || (highest ? quotient.compareTo(extreme) > 0 : quotient.compareTo(extreme) < 0)) {
                extreme = quotient;
            }
        }
        Fraction target = extreme;
        List<Candidate> tied = hopeful.stream().filter(c -> quotients.get(c).compareTo(target) == 0).toList();
        if (tied.size() == 1) {
            return tied.getFirst();
        }
        return highest
                ? tieBreak.candidateToElectFirst(tied, List.of())
                : tieBreak.candidateToEliminate(tied, List.of());
    }

    /**
     * The same count over arrays: identical rankings merged, each held as candidate indices with
     * a {@code long} count, and every per-candidate total an array entry. A ballot's holder only
     * ever needs its next hopeful preference, and a candidate once excluded never returns, so
     * each ballot remembers the position of its first not-yet-excluded preference: a restart
     * resumes from there rather than re-walking from the first preference. Elected credits stay
     * exact {@link Fraction}s, grouped by value as in {@link #weightedTotal}; most ballots carry
     * none at all.
     *
     * <p>Each candidate's ballots are kept in a list, and the totals a stage needs (votes,
     * active votes, ballots by credit) are kept up to date as ballots move, so an election or an
     * exclusion touches only the ballots it moves: scanning every ballot each stage, with
     * thousands of stages once exclusions restart the count, was most of a large count's time.
     * A ballot leaves a candidate only when they're elected or excluded, all of their ballots at
     * once, so each list is singly linked through the ballots, one {@code int} a ballot: lists of
     * arrays, grown by doubling, took 20 MB more at a million ballots, and the count is close to
     * its heap there. A ballot's credit is an index into the credits given since the last
     * restart, at most one per seat.
     */
    private static final class IndexedCount {
        private final List<Candidate> candidates;
        private final Map<Candidate, Integer> indexOf = new LinkedHashMap<>();
        private final int seats;
        private final TieBreakRule tieBreak;
        private final int[][] rankings;
        private final long[] counts;
        private final int[] firstNotExcluded;
        private final int[] next;
        private final int[] holder;
        private final boolean[] isHopeful;
        private final boolean[] isExcluded;
        // Which of creditValues each ballot carries (-1 for none).
        private final int[] creditOf;
        private final List<Fraction> creditValues = new ArrayList<>();
        // Each candidate's ballots: firstHeld[c], then nextHeld of each in turn, -1 ending it.
        private final int[] firstHeld;
        private final int[] nextHeld;
        // Running totals: votes per candidate; active votes; ballots per (holder, credit), at
        // holder * seats + credit; exhausted ballots per credit.
        private final long[] votes;
        private long active;
        private final long[] heldByCredit;
        private final long[] exhaustedByCredit;

        IndexedCount(List<Candidate> candidates, List<Ballot> ballots, int seats, TieBreakRule tieBreak) {
            this.candidates = candidates;
            this.seats = seats;
            this.tieBreak = tieBreak;
            for (int c = 0; c < candidates.size(); c++) {
                indexOf.put(candidates.get(c), c);
            }
            Merged merged = merge(ballots, indexOf);
            this.rankings = merged.rankings();
            this.counts = merged.counts();
            int b = rankings.length;
            this.firstNotExcluded = new int[b];
            this.next = new int[b];
            this.holder = new int[b];
            this.isHopeful = new boolean[candidates.size()];
            this.isExcluded = new boolean[candidates.size()];
            this.creditOf = new int[b];
            this.firstHeld = new int[candidates.size()];
            this.nextHeld = new int[b];
            this.votes = new long[candidates.size()];
            this.heldByCredit = new long[candidates.size() * seats];
            this.exhaustedByCredit = new long[seats];
        }

        /** Identical rankings merged: each distinct one, and how many ballots gave it. */
        private record Merged(int[][] rankings, long[] counts) {
        }

        /**
         * Merges identical rankings, keyed by the int[] itself: keying them by a List<Integer> boxed
         * every candidate number, about 600 MB for a million ballots ranking 40. A method of its own
         * so the map is garbage once it returns, before the count's own arrays are allocated.
         */
        private static Merged merge(List<Ballot> ballots, Map<Candidate, Integer> indexOf) {
            Map<RankingKey, Integer> seen = new HashMap<>();
            List<int[]> distinct = new ArrayList<>();
            long[] distinctCounts = new long[16];
            for (Ballot ballot : ballots) {
                if (ballot.isBlank()) {
                    continue;
                }
                // Ties never reach here (split is refused; the other policies remove them).
                // A candidate this count doesn't know about can never be hopeful: dropped.
                int[] ranking = ballot.ranking().stream().map(rank -> indexOf.get(rank.iterator().next()))
                        .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).toArray();
                Integer at = seen.putIfAbsent(new RankingKey(ranking), distinct.size());
                if (at == null) {
                    if (distinct.size() == distinctCounts.length) {
                        distinctCounts = Arrays.copyOf(distinctCounts, distinctCounts.length * 2);
                    }
                    distinctCounts[distinct.size()] = ballot.count().longValueExact();
                    distinct.add(ranking);
                } else {
                    distinctCounts[at] += ballot.count().longValueExact();
                }
            }
            return new Merged(distinct.toArray(int[][]::new), Arrays.copyOf(distinctCounts, distinct.size()));
        }

        /** A ranking with value equality, for merging identical ballots. */
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

        QpqResult run(BallotFormality.FormalBallots formal) {
            Set<Candidate> hopeful = new LinkedHashSet<>(candidates);
            Arrays.fill(isHopeful, true);
            // See the LinkedHashSet note in countInBigIntegers.
            Set<Candidate> elected = new LinkedHashSet<>();
            List<QpqRoundRecord> rounds = new ArrayList<>();
            int stageNumber = 0;
            boolean restartPending = true;

            while (elected.size() < seats && hopeful.size() > seats - elected.size()) {
                Interruption.check();
                stageNumber++;
                boolean restartedThisStage = restartPending;
                if (restartPending) {
                    restart(hopeful, elected);
                    restartPending = false;
                }
                Tally tally = tally();
                Map<Candidate, Fraction> quotients = quotients(hopeful, tally);
                Fraction quota = Fraction.of(tally.active())
                        .divide(Fraction.of(seats + 1L).subtract(weightedTotal(tally.exhaustedByCredit())));

                Candidate highest = pickExtreme(hopeful, quotients, true, tieBreak);
                if (quotients.get(highest).compareTo(quota) > 0) {
                    elect(highest, quotients.get(highest), hopeful, elected);
                    rounds.add(new QpqRoundRecord(
                            stageNumber, quotients, quota, new QpqAction.Elected(highest), restartedThisStage));
                } else {
                    Candidate lowest = pickExtreme(hopeful, quotients, false, tieBreak);
                    exclude(lowest, hopeful);
                    rounds.add(new QpqRoundRecord(
                            stageNumber, quotients, quota, new QpqAction.Excluded(lowest), restartedThisStage));
                    restartPending = true;
                }
            }

            if (elected.size() < seats) {
                elected.addAll(hopeful);
            }
            return new QpqResult(List.copyOf(elected), rounds, formal.informal());
        }

        /**
         * Starts the count again after an exclusion: everyone elected is hopeful once more, and every
         * ballot goes back to its first preference not yet excluded, with no credit.
         */
        private void restart(Set<Candidate> hopeful, Set<Candidate> elected) {
            for (Candidate candidate : elected) {
                isHopeful[indexOf.get(candidate)] = true;
            }
            hopeful.addAll(elected);
            elected.clear();
            creditValues.clear();
            Arrays.fill(firstHeld, -1);
            Arrays.fill(votes, 0);
            active = 0;
            Arrays.fill(heldByCredit, 0);
            Arrays.fill(exhaustedByCredit, 0);
            for (int b = 0; b < rankings.length; b++) {
                reset(b);
            }
        }

        /** Sends ballot {@code b} to its first preference not yet excluded, with no credit. */
        private void reset(int b) {
            int[] ranking = rankings[b];
            int first = firstNotExcluded[b];
            while (first < ranking.length && isExcluded[ranking[first]]) {
                first++;
            }
            firstNotExcluded[b] = first;
            next[b] = first;
            creditOf[b] = -1;
            advance(b);
            join(b);
        }

        /** Moves ballot {@code b} on from a candidate elected or excluded. */
        private void moveOn(int b, int newCredit) {
            leave(b);
            if (newCredit >= 0) {
                creditOf[b] = newCredit;
            }
            advance(b);
            join(b);
        }

        /**
         * A stage's ballots: each candidate's votes, the active votes in all, and the ballots carrying
         * credit, by credit, for each candidate (null where none) and among the exhausted.
         */
        private record Tally(long[] votes, long active, List<Map<Fraction, BigInteger>> heldByCredit,
                Map<Fraction, BigInteger> exhaustedByCredit) {
        }

        private Tally tally() {
            List<Map<Fraction, BigInteger>> held = new ArrayList<>(candidates.size());
            for (int c = 0; c < candidates.size(); c++) {
                Map<Fraction, BigInteger> byCredit = null;
                for (int k = 0; k < creditValues.size(); k++) {
                    long count = heldByCredit[c * seats + k];
                    if (count != 0) {
                        if (byCredit == null) {
                            byCredit = new LinkedHashMap<>();
                        }
                        byCredit.merge(creditValues.get(k), BigInteger.valueOf(count), BigInteger::add);
                    }
                }
                held.add(byCredit);
            }
            Map<Fraction, BigInteger> exhausted = new LinkedHashMap<>();
            for (int k = 0; k < creditValues.size(); k++) {
                if (exhaustedByCredit[k] != 0) {
                    exhausted.merge(creditValues.get(k), BigInteger.valueOf(exhaustedByCredit[k]), BigInteger::add);
                }
            }
            return new Tally(votes.clone(), active, held, exhausted);
        }

        /** Each hopeful candidate's quotient: their votes over one plus the credit their ballots carry. */
        private Map<Candidate, Fraction> quotients(Set<Candidate> hopeful, Tally tally) {
            Map<Candidate, Fraction> quotients = new LinkedHashMap<>();
            for (Candidate candidate : hopeful) {
                int c = indexOf.get(candidate);
                Map<Fraction, BigInteger> held = tally.heldByCredit().get(c);
                Fraction credits = held == null ? Fraction.ZERO : weightedTotal(held);
                quotients.put(candidate, Fraction.of(tally.votes()[c]).divide(Fraction.ONE.add(credits)));
            }
            return quotients;
        }

        /** Elects a candidate: their ballots take credit of one over their quotient and move on. */
        private void elect(Candidate candidate, Fraction quotient, Set<Candidate> hopeful, Set<Candidate> elected) {
            elected.add(candidate);
            hopeful.remove(candidate);
            int h = indexOf.get(candidate);
            isHopeful[h] = false;
            int newCredit = creditValues.size();
            creditValues.add(Fraction.ONE.divide(quotient));
            // Saving each next first: moving a ballot on links it into another list.
            for (int b = firstHeld[h], following; b >= 0; b = following) {
                following = nextHeld[b];
                moveOn(b, newCredit);
            }
            firstHeld[h] = -1;
        }

        /** Excludes a candidate for good: their ballots move on (the count restarts at the next stage). */
        private void exclude(Candidate candidate, Set<Candidate> hopeful) {
            hopeful.remove(candidate);
            int l = indexOf.get(candidate);
            isHopeful[l] = false;
            isExcluded[l] = true;
            for (int b = firstHeld[l], following; b >= 0; b = following) {
                following = nextHeld[b];
                moveOn(b, -1);
            }
            firstHeld[l] = -1;
        }

        /** Takes ballot {@code b} out of the running totals (its holder's list is emptied after). */
        private void leave(int b) {
            int h = holder[b];
            if (h < 0) {
                if (creditOf[b] >= 0) {
                    exhaustedByCredit[creditOf[b]] -= counts[b];
                }
                return;
            }
            votes[h] -= counts[b];
            active -= counts[b];
            if (creditOf[b] >= 0) {
                heldByCredit[h * seats + creditOf[b]] -= counts[b];
            }
        }

        /** Adds ballot {@code b} to its holder's list and the running totals. */
        private void join(int b) {
            int h = holder[b];
            if (h < 0) {
                if (creditOf[b] >= 0) {
                    exhaustedByCredit[creditOf[b]] += counts[b];
                }
                return;
            }
            votes[h] += counts[b];
            active += counts[b];
            if (creditOf[b] >= 0) {
                heldByCredit[h * seats + creditOf[b]] += counts[b];
            }
            nextHeld[b] = firstHeld[h];
            firstHeld[h] = b;
        }

        /** Moves ballot {@code b} on to its next hopeful preference, or marks it exhausted. */
        private void advance(int b) {
            int[] ranking = rankings[b];
            while (next[b] < ranking.length) {
                int candidate = ranking[next[b]++];
                if (isHopeful[candidate]) {
                    holder[b] = candidate;
                    return;
                }
            }
            holder[b] = -1;
        }
    }

    /** The sum of each credit times how many ballots carry it. */
    private static Fraction weightedTotal(Map<Fraction, BigInteger> ballotsByCredit) {
        Fraction total = Fraction.ZERO;
        for (Map.Entry<Fraction, BigInteger> byCredit : ballotsByCredit.entrySet()) {
            if (byCredit.getKey().signum() != 0) {
                total = total.add(Fraction.of(byCredit.getValue()).multiply(byCredit.getKey()));
            }
        }
        return total;
    }

    private static final class BallotState {
        private final Ballot ballot;
        private int nextIndex = 0;
        private Candidate holder;
        private Fraction electedCredit = Fraction.ZERO;

        BallotState(Ballot ballot) {
            this.ballot = ballot;
        }
    }

    private static void assignNextHolder(BallotState state, Set<Candidate> hopeful) {
        List<Set<Candidate>> ranking = state.ballot.ranking();
        while (state.nextIndex < ranking.size()) {
            Candidate candidate = ranking.get(state.nextIndex).iterator().next();
            state.nextIndex++;
            if (hopeful.contains(candidate)) {
                state.holder = candidate;
                return;
            }
        }
        state.holder = null;
    }
}
