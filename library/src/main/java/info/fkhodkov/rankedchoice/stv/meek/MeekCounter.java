package info.fkhodkov.rankedchoice.stv.meek;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.core.util.Interruption;
import info.fkhodkov.rankedchoice.stv.BallotFormality;
import info.fkhodkov.rankedchoice.stv.RoundAction;
import info.fkhodkov.rankedchoice.stv.RoundRecord;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;

/**
 * Runs an STV count by Meek's method (Hill, Wichmann &amp; Woodall, "Algorithm 123: Single
 * Transferable Vote by Meek's Method", <i>The Computer Journal</i> 30(3), 1987), or by Warren's
 * method, which differs only in how a keep factor applies to a ballot (see {@link KeepRule}) —
 * structurally
 * different from every {@link info.fkhodkov.rankedchoice.stv.transfer.TransferMethod} in this
 * project, so it isn't one: instead of tracking which single "parcel" a ballot most recently
 * arrived through and moving only the ballots touched by one election/elimination event, every
 * ballot's value is recomputed from scratch, every iteration, by walking its full ranking
 * against every candidate's current keep factor.
 *
 * <p>Each candidate is hopeful (keep factor 1: keeps everything reaching them), elected (keeps
 * a fraction, its keep factor, passing the rest to the ballot's next preference), or excluded
 * (keep factor 0). A ballot ranking A, B, C with keep factors a and b (C hopeful) gives A
 * {@code a}, B {@code (1-a)*b}, and C the remainder {@code (1-a)*(1-b)}.
 *
 * <p>The count follows droop's Meek rule ({@code jklundell/droop}, {@code rules/meek.py}, with
 * {@code arithmetic=fixed defeat_batch=none}, or droop's {@code warren} rule, the same code)
 * step for step, in the same fixed-point decimal
 * arithmetic ({@link FixedPoint}, {@link MeekRuleset#precision()} places), so every figure is
 * reproducible exactly rather than to within a tolerance:
 * <ul>
 * <li>Each candidate keeps {@code weight * keepFactor} of what reaches them and passes on
 * {@code weight * (1 - keepFactor)}, both rounded down — so a little value is lost to rounding
 * at every elected candidate, and counts as part of the residual ("exhausted") votes. (Under
 * Warren's method, {@code min(keepFactor, weight)} and the exact remainder instead.)
 * <li>After each distribution the quota is recomputed from the votes candidates actually hold:
 * {@code votes / (seats + 1)}, rounded down, plus the smallest representable amount, and a
 * hopeful is elected on reaching it. Everyone who reaches it in the same iteration is elected
 * together, which ends the round.
 * <li>Otherwise, each elected candidate's keep factor becomes {@code keepFactor * quota /
 * votes}, rounding up after both the multiplication and the division, and the ballots are
 * distributed again — until the total surplus over quota is at most {@code omega}
 * (10^-(2/3 of precision), e.g. 10^-6 at 9 places) or stops shrinking. Then the lowest hopeful
 * is excluded; every hopeful within that remaining surplus of the lowest counts as tied with it.
 * <li>Once the hopefuls left can only just fill the seats left, they're all elected on the
 * votes from the last distribution, without iterating again.
 * </ul>
 *
 * <p>Each round's tallies are those of the distribution the round's decision was made on, and
 * an {@code Elected} action's surplus is that candidate's votes minus that distribution's quota.
 * {@link StvResult#quota()} reports the quota as of the last iteration only — a known
 * simplification, since Meek's quota (unlike every other method here) genuinely changes as the
 * count goes on.
 *
 * <p>Checked against droop itself: on MapLibre's real 2025 Governing Board election (see
 * {@code MeekMapLibreGoverningBoard2025FixtureTest}) and on synthetic elections with and without
 * tied rankings, every round's tallies, quota and residual match droop's report to the last
 * decimal place.
 *
 * <p>Candidates must already exclude any withdrawn from standing. Tied rankings and short
 * ballots are handled per the ruleset's policy (see {@link MeekRuleset}); under {@code split},
 * a tied rank shares whatever reaches it evenly between its standing candidates, following
 * droop's Meek rule (see {@link #distribute}).
 */
public final class MeekCounter {

    private enum State { HOPEFUL, ELECTED, EXCLUDED }

    /** Distinct ballots below which one distribution pass isn't worth splitting across cores. */
    private static final int PARALLEL_THRESHOLD = 20_000;

    private MeekCounter() {
    }

    public static StvResult count(List<Candidate> candidates, List<Ballot> rawBallots, int seats, MeekRuleset ruleset) {
        validate(candidates, seats);
        BallotFormality.FormalBallots formal = BallotFormality.apply(
                rawBallots, ruleset.tiedRankings(), ruleset.minimumPreferences(), candidates.size());
        Count count = new Count(
                candidates, formal.ballots(), seats, new FixedPoint(ruleset.precision()), ruleset.keepRule());
        return count.run(ruleset, formal);
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

    /** One distribution of every ballot: each standing candidate's votes, and the quota they imply. */
    private record Distribution(Map<Candidate, Long> votes, long residual, long quota) {
    }

    private static final class Count {
        private final List<Candidate> candidates;
        private final Map<Candidate, Integer> indexOf = new LinkedHashMap<>();
        private final int seats;
        private final FixedPoint fixed;
        private final KeepRule keepRule;
        private final long totalVotes;
        private final long omega;
        // Every distinct ranking once, as candidate indices, and how many ballots cast it: the form
        // the per-iteration distribution walks, built once. Each ranking is one flat array, every
        // rank's members in turn with its last stored as ~index (see indexed), not an array per
        // rank: a million ballots ranking 40 would otherwise be 40 million arrays, about 1 GB.
        private final int[][] rankings;
        private final long[] multipliers;
        // Indexed like `candidates`; the count's only record of each candidate's state.
        private final State[] states;
        private final long[] keepFactors;
        // Bumped on every change of state; the ballots are sorted again when it no longer matches.
        private int statesVersion;
        // Between changes of state only keep factors change, and a ballot whose first rank with
        // anyone standing has only hopeful candidates gives them its whole value whatever those are:
        // such ballots are summed once into fixedVotes, and each pass walks only the rest, in
        // movingBallots (by chunk, as distribute splits them). On a 300-candidate count about 60%
        // of the ballots a pass would visit are fixed.
        private int splitVersion = -1;
        private long[] fixedVotes;
        private int[][] movingBallots;

        Count(List<Candidate> candidates, List<Ballot> ballots, int seats, FixedPoint fixed, KeepRule keepRule) {
            this.candidates = candidates;
            this.seats = seats;
            this.fixed = fixed;
            this.keepRule = keepRule;
            for (int i = 0; i < candidates.size(); i++) {
                indexOf.put(candidates.get(i), i);
            }
            // Checked before any count is taken as a long: within the limit, every count fits.
            BigInteger ballotCount = ballots.stream().map(Ballot::count).reduce(BigInteger.ZERO, BigInteger::add);
            fixed.requireRoomFor(ballotCount);
            Map<RankingKey, Integer> seen = new LinkedHashMap<>();
            List<int[]> distinct = new ArrayList<>();
            List<Long> counts = new ArrayList<>();
            for (Ballot ballot : ballots) {
                int[] ranking = indexed(ballot.ranking());
                long multiplier = ballot.count().longValueExact();
                Integer at = seen.putIfAbsent(new RankingKey(ranking), distinct.size());
                if (at == null) {
                    distinct.add(ranking);
                    counts.add(multiplier);
                } else {
                    counts.set(at, Math.addExact(counts.get(at), multiplier));
                }
            }
            this.totalVotes = fixed.of(ballotCount);
            this.omega = fixed.tenToTheMinus(fixed.places() * 2 / 3);
            this.rankings = distinct.toArray(int[][]::new);
            this.multipliers = counts.stream().mapToLong(Long::longValue).toArray();
            this.states = new State[candidates.size()];
            this.keepFactors = new long[candidates.size()];
            Arrays.fill(states, State.HOPEFUL);
            Arrays.fill(keepFactors, fixed.one());
        }

        /**
         * A ranking as candidate indices, flat: each rank's members in increasing order, the last
         * of them stored as {@code ~index} (negative, so it marks where the rank ends), so a rank
         * of one candidate c is just {@code ~c}. A candidate this count doesn't know about (e.g.
         * withdrawn) can never be standing, so it's dropped here — along with a rank left with
         * nobody in it, which the distribution would only have skipped over anyway.
         */
        private int[] indexed(List<Set<Candidate>> ranking) {
            int[] flat = new int[ranking.stream().mapToInt(Set::size).sum()];
            int length = 0;
            for (Set<Candidate> rank : ranking) {
                int[] members = rank.stream().filter(indexOf::containsKey).mapToInt(indexOf::get).sorted().toArray();
                if (members.length > 0) {
                    System.arraycopy(members, 0, flat, length, members.length);
                    length += members.length;
                    flat[length - 1] = ~flat[length - 1];
                }
            }
            return length == flat.length ? flat : Arrays.copyOf(flat, length);
        }

        /** An indexed ranking with value equality, for merging identical ballots. */
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

        private State state(Candidate candidate) {
            return states[indexOf.get(candidate)];
        }

        private void setState(Candidate candidate, State state) {
            states[indexOf.get(candidate)] = state;
            statesVersion++;
        }

        StvResult run(MeekRuleset ruleset, BallotFormality.FormalBallots formal) {
            List<Candidate> elected = new ArrayList<>();
            List<RoundRecord> rounds = new ArrayList<>();
            // Before any iteration, droop's quota comes from every valid vote, not the votes held.
            Distribution initial = distribute();
            Distribution latest = new Distribution(initial.votes(), initial.residual(),
                    fixed.divideDown(totalVotes, seats + 1) + fixed.epsilon());
            int roundNumber = 0;

            while (hopefuls().size() > seats - elected.size() && elected.size() < seats) {
                roundNumber++;
                Iteration iteration = iterate();
                latest = iteration.distribution();
                List<RoundRecord> priorRounds = List.copyOf(rounds);
                Map<Candidate, Fraction> tallies = hopefulTallies(latest, iteration.newlyElected(), ruleset);
                Fraction residual = display(latest.residual(), ruleset);

                if (!iteration.newlyElected().isEmpty()) {
                    List<Candidate> inOrder = byVotesDescending(
                            iteration.newlyElected(), latest, ruleset.tieBreak(), priorRounds);
                    for (int i = 0; i < inOrder.size(); i++) {
                        Candidate candidate = inOrder.get(i);
                        elected.add(candidate);
                        Fraction surplus = display(latest.votes().get(candidate) - latest.quota(), ruleset);
                        rounds.add(new RoundRecord(roundNumber, tallies,
                                new RoundAction.Elected(candidate, surplus), residual));
                        if (i < inOrder.size() - 1) {
                            roundNumber++;
                        }
                    }
                    continue;
                }

                Candidate excluded = lowestHopeful(latest, iteration.surplus(), ruleset.tieBreak(), priorRounds);
                setState(excluded, State.EXCLUDED);
                keepFactors[indexOf.get(excluded)] = 0L;
                rounds.add(new RoundRecord(roundNumber, tallies, new RoundAction.Eliminated(excluded), residual));
                // droop redistributes after an exclusion for reporting, keeping the iteration's quota.
                Distribution redistributed = distribute();
                latest = new Distribution(redistributed.votes(), redistributed.residual(), latest.quota());
            }

            if (elected.size() < seats) {
                // Everyone still hopeful is needed to fill the seats: elected as they stand.
                Set<Candidate> remaining = hopefuls();
                Map<Candidate, Fraction> tallies = hopefulTallies(latest, Set.of(), ruleset);
                Fraction residual = display(latest.residual(), ruleset);
                List<RoundRecord> priorRounds = List.copyOf(rounds);
                for (Candidate candidate : byVotesDescending(remaining, latest, ruleset.tieBreak(), priorRounds)) {
                    roundNumber++;
                    setState(candidate, State.ELECTED);
                    elected.add(candidate);
                    rounds.add(new RoundRecord(roundNumber, tallies,
                            new RoundAction.Elected(candidate, Fraction.ZERO), residual));
                }
            }

            return new StvResult(elected, rounds, display(latest.quota(), ruleset), formal.informal());
        }

        private record Iteration(Distribution distribution, Set<Candidate> newlyElected, long surplus) {
        }

        /**
         * droop's {@code iterate}: distribute, recompute the quota, elect whoever reaches it,
         * and otherwise update keep factors and go again, until someone is elected or the
         * surplus is at most {@code omega} or has stopped shrinking.
         */
        private Iteration iterate() {
            long lastSurplus = totalVotes;
            while (true) {
                Distribution distribution = distribute();
                Set<Candidate> newlyElected = new LinkedHashSet<>();
                for (Candidate candidate : hopefuls()) {
                    if (distribution.votes().get(candidate) >= distribution.quota()) {
                        newlyElected.add(candidate);
                    }
                }
                if (newlyElected.size() > seats - electedCount()) {
                    throw new IllegalStateException("More candidates reached quota than there are seats left");
                }
                newlyElected.forEach(candidate -> setState(candidate, State.ELECTED));

                long surplus = 0;
                for (Candidate candidate : candidates) {
                    if (state(candidate) == State.ELECTED) {
                        surplus += distribution.votes().get(candidate) - distribution.quota();
                    }
                }
                if (!newlyElected.isEmpty() || surplus <= omega || surplus >= lastSurplus) {
                    return new Iteration(distribution, newlyElected, surplus);
                }
                lastSurplus = surplus;

                for (int c = 0; c < candidates.size(); c++) {
                    if (states[c] == State.ELECTED) {
                        long scaled = fixed.multiply(keepFactors[c], distribution.quota(), true);
                        keepFactors[c] = fixed.divide(scaled, distribution.votes().get(candidates.get(c)), true);
                    }
                }
            }
        }

        private Distribution distribute() {
            Interruption.check();
            long[] passFactors = new long[candidates.size()];
            for (int c = 0; c < candidates.size(); c++) {
                passFactors[c] = fixed.one() - keepFactors[c];
            }
            // After a change of state, this pass walks every ballot and sorts them as it goes (see
            // movingBallots); until the next, passes walk only the moving ones.
            boolean resplit = splitVersion != statesVersion;
            int chunks = !resplit ? movingBallots.length : rankings.length < PARALLEL_THRESHOLD ? 1 : Math.min(
                    rankings.length / (PARALLEL_THRESHOLD / 4), 4 * Runtime.getRuntime().availableProcessors());
            // Each ballot's contribution is independent and every sum is an exact integer, so
            // summing chunks on separate cores and adding them up gives the same totals.
            long[][] partial = new long[chunks][];
            long[][] partialFixed = new long[chunks][];
            int[][] moving = new int[chunks][];
            IntStream.range(0, chunks).parallel().forEach(chunk -> {
                long[] chunkVotes = new long[candidates.size()];
                if (resplit) {
                    long[] chunkFixed = new long[candidates.size()];
                    int from = (int) ((long) rankings.length * chunk / chunks);
                    int to = (int) ((long) rankings.length * (chunk + 1) / chunks);
                    int[] chunkMoving = new int[to - from];
                    int count = 0;
                    for (int b = from; b < to; b++) {
                        if (!addIfFixed(b, chunkFixed)) {
                            chunkMoving[count++] = b;
                            distribute(rankings[b], 0, fixed.one(), multipliers[b], chunkVotes, passFactors);
                        }
                    }
                    partialFixed[chunk] = chunkFixed;
                    moving[chunk] = Arrays.copyOf(chunkMoving, count);
                } else {
                    for (int b : movingBallots[chunk]) {
                        distribute(rankings[b], 0, fixed.one(), multipliers[b], chunkVotes, passFactors);
                    }
                }
                partial[chunk] = chunkVotes;
            });
            if (resplit) {
                long[] fixedSum = new long[candidates.size()];
                for (long[] chunkFixed : partialFixed) {
                    for (int c = 0; c < fixedSum.length; c++) {
                        fixedSum[c] = Math.addExact(fixedSum[c], chunkFixed[c]);
                    }
                }
                fixedVotes = fixedSum;
                movingBallots = moving;
                splitVersion = statesVersion;
            }
            long[] votes = fixedVotes.clone();
            for (long[] chunkVotes : partial) {
                for (int c = 0; c < votes.length; c++) {
                    votes[c] = Math.addExact(votes[c], chunkVotes[c]);
                }
            }
            Map<Candidate, Long> byCandidate = new LinkedHashMap<>();
            long held = 0;
            for (int c = 0; c < candidates.size(); c++) {
                if (states[c] != State.EXCLUDED) {
                    byCandidate.put(candidates.get(c), votes[c]);
                    held += votes[c];
                }
            }
            long quota = fixed.divideDown(held, seats + 1) + fixed.epsilon();
            return new Distribution(byCandidate, totalVotes - held, quota);
        }

        /**
         * If ballot {@code b}'s first rank with anyone standing has only hopeful candidates, adds
         * what it gives them to {@code votes}, exactly as {@link #distribute(int[], int, long, long,
         * long[], long[])} would (the weight split evenly, rounded down, and all of it kept), and
         * says so; a ballot with nobody standing gives nothing and counts as fixed too.
         */
        private boolean addIfFixed(int b, long[] votes) {
            int[] ranking = rankings[b];
            int start = 0;
            while (start < ranking.length) {
                int last = start;
                while (ranking[last] >= 0) {
                    last++;
                }
                int standing = 0;
                for (int j = start; j <= last; j++) {
                    State state = states[member(ranking[j])];
                    if (state == State.ELECTED) {
                        return false;
                    }
                    if (state == State.HOPEFUL) {
                        standing++;
                    }
                }
                if (standing > 0) {
                    long kept = Math.multiplyExact(fixed.divideDown(fixed.one(), standing), multipliers[b]);
                    for (int j = start; j <= last; j++) {
                        int candidate = member(ranking[j]);
                        if (states[candidate] == State.HOPEFUL) {
                            votes[candidate] = Math.addExact(votes[candidate], kept);
                        }
                    }
                    return true;
                }
                start = last + 1;
            }
            return true;
        }

        /**
         * Passes {@code weight} (per ballot, times {@code multiplier} identical ballots) down
         * {@code ranking} from {@code index}: each standing candidate keeps part of it by the
         * {@link KeepRule} and passes on the rest (under Meek, {@code weight * keepFactor} kept
         * and {@code weight * (1 - keepFactor)} passed on, both rounded down). A tied
         * rank splits the weight reaching it evenly (rounded down) between its standing
         * candidates, and what each one passes on flows to the next rank separately — the
         * equal-preference distribution in droop's Meek rule ({@code distributeVotes}). One
         * deliberate difference: droop routes any ballot containing a tie through a separate path
         * that stops outright at the first rank with no standing candidate — tied or not — losing
         * the rest of the ballot's value, whereas its path for untied ballots skips excluded
         * candidates and carries on. This carries on in both cases, and matches a copy of droop
         * with just that one path patched to carry on.
         *
         * <p>This runs for every distinct ballot on every iteration, so it works on candidate
         * indices and arrays only. A hopeful candidate (keep factor 1) keeps the whole share and
         * passes nothing on — exactly what either keep rule computes for a keep factor of 1 — so
         * that case skips the multiplication.
         */
        private void distribute(int[] ranking, int from, long weight, long multiplier, long[] votes,
                long[] passFactors) {
            int start = from;
            while (start < ranking.length && weight > 0) {
                int last = start; // the rank's last member, the one stored as ~index
                while (ranking[last] >= 0) {
                    last++;
                }
                int next = last + 1;
                int standing = 0;
                for (int j = start; j <= last; j++) {
                    if (states[member(ranking[j])] != State.EXCLUDED) {
                        standing++;
                    }
                }
                if (standing == 0) {
                    start = next;
                    continue;
                }
                long share = fixed.divideDown(weight, standing);
                for (int j = start; j <= last; j++) {
                    int candidate = member(ranking[j]);
                    if (states[candidate] == State.EXCLUDED) {
                        continue;
                    }
                    long kept;
                    long passedOn;
                    if (states[candidate] == State.HOPEFUL) {
                        kept = share;
                        passedOn = 0;
                    } else {
                        long keepFactor = keepFactors[candidate];
                        kept = switch (keepRule) {
                            case MEEK -> fixed.multiply(share, keepFactor, false);
                            case WARREN -> Math.min(keepFactor, share);
                        };
                        passedOn = switch (keepRule) {
                            case MEEK -> fixed.multiply(share, passFactors[candidate], false);
                            case WARREN -> share - kept;
                        };
                    }
                    votes[candidate] = Math.addExact(votes[candidate], Math.multiplyExact(kept, multiplier));
                    if (standing > 1) {
                        distribute(ranking, next, passedOn, multiplier, votes, passFactors);
                    } else {
                        weight = passedOn;
                    }
                }
                if (standing > 1) {
                    return;
                }
                start = next;
            }
        }

        /** A candidate index as {@link #indexed} stores it, the last of its rank as {@code ~index}. */
        private static int member(int stored) {
            return stored < 0 ? ~stored : stored;
        }

        /**
         * The hopeful to exclude: the lowest, tied with every hopeful within the remaining surplus of
         * it. The surplus is the elected candidates' votes less the quota, summed as droop sums it,
         * and rounding can leave it below zero (an elected candidate a hair under the quota); then
         * there's nothing left to transfer, and only candidates on exactly the lowest vote tie. droop
         * itself stops there, its list of tied candidates empty.
         */
        private Candidate lowestHopeful(Distribution distribution, long surplus, TieBreakRule tieBreak,
                List<RoundRecord> priorRounds) {
            long lowest = hopefuls().stream().mapToLong(distribution.votes()::get).min().orElseThrow();
            long within = Math.max(surplus, 0);
            List<Candidate> tied = hopefuls().stream()
                    .filter(candidate -> distribution.votes().get(candidate) <= lowest + within)
                    .toList();
            return tied.size() == 1 ? tied.getFirst() : tieBreak.candidateToEliminate(tied, priorRounds);
        }

        private List<Candidate> byVotesDescending(Set<Candidate> group, Distribution distribution,
                TieBreakRule tieBreak, List<RoundRecord> priorRounds) {
            List<Candidate> remaining = new ArrayList<>(group);
            List<Candidate> result = new ArrayList<>(remaining.size());
            while (!remaining.isEmpty()) {
                long highest = remaining.stream().mapToLong(distribution.votes()::get).max().orElseThrow();
                List<Candidate> tied = remaining.stream()
                        .filter(candidate -> distribution.votes().get(candidate) == highest)
                        .toList();
                Candidate top = tied.size() == 1 ? tied.getFirst() : tieBreak.candidateToElectFirst(tied, priorRounds);
                result.add(top);
                remaining.remove(top);
            }
            return result;
        }

        /**
         * The round's tallies: every candidate hopeful going into it (including any it just
         * elected), in declared order — matches {@code StvCounter}'s display convention.
         */
        private Map<Candidate, Fraction> hopefulTallies(
                Distribution distribution, Set<Candidate> justElected, MeekRuleset ruleset) {
            Map<Candidate, Fraction> tallies = new LinkedHashMap<>();
            for (Candidate candidate : candidates) {
                if (state(candidate) == State.HOPEFUL || justElected.contains(candidate)) {
                    tallies.put(candidate, display(distribution.votes().get(candidate), ruleset));
                }
            }
            return tallies;
        }

        private Fraction display(long value, MeekRuleset ruleset) {
            return ruleset.roundingRule().apply(fixed.toFraction(value));
        }

        private Set<Candidate> hopefuls() {
            Set<Candidate> result = new LinkedHashSet<>();
            for (int c = 0; c < candidates.size(); c++) {
                if (states[c] == State.HOPEFUL) {
                    result.add(candidates.get(c));
                }
            }
            return result;
        }

        private int electedCount() {
            return (int) Arrays.stream(states).filter(state -> state == State.ELECTED).count();
        }
    }
}
