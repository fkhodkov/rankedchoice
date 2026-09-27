package info.fkhodkov.rankedchoice.stv.meek;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
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
        private final List<Ballot> ballots;
        private final int seats;
        private final FixedPoint fixed;
        private final KeepRule keepRule;
        private final long totalVotes;
        private final long omega;
        private final Map<Candidate, State> states = new LinkedHashMap<>();
        private final Map<Candidate, Long> keepFactors = new LinkedHashMap<>();

        Count(List<Candidate> candidates, List<Ballot> ballots, int seats, FixedPoint fixed, KeepRule keepRule) {
            this.candidates = candidates;
            this.ballots = ballots;
            this.seats = seats;
            this.fixed = fixed;
            this.keepRule = keepRule;
            BigInteger ballotCount = BigInteger.ZERO;
            for (Ballot ballot : ballots) {
                ballotCount = ballotCount.add(ballot.count());
            }
            fixed.requireRoomFor(ballotCount);
            this.totalVotes = fixed.of(ballotCount);
            this.omega = fixed.tenToTheMinus(fixed.places() * 2 / 3);
            for (Candidate candidate : candidates) {
                states.put(candidate, State.HOPEFUL);
                keepFactors.put(candidate, fixed.one());
            }
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
                states.put(excluded, State.EXCLUDED);
                keepFactors.put(excluded, 0L);
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
                    states.put(candidate, State.ELECTED);
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
                newlyElected.forEach(candidate -> states.put(candidate, State.ELECTED));

                long surplus = 0;
                for (Candidate candidate : candidates) {
                    if (states.get(candidate) == State.ELECTED) {
                        surplus += distribution.votes().get(candidate) - distribution.quota();
                    }
                }
                if (!newlyElected.isEmpty() || surplus <= omega || surplus >= lastSurplus) {
                    return new Iteration(distribution, newlyElected, surplus);
                }
                lastSurplus = surplus;

                for (Candidate candidate : candidates) {
                    if (states.get(candidate) == State.ELECTED) {
                        long scaled = fixed.multiply(keepFactors.get(candidate), distribution.quota(), true);
                        keepFactors.put(candidate, fixed.divide(scaled, distribution.votes().get(candidate), true));
                    }
                }
            }
        }

        private Distribution distribute() {
            Map<Candidate, Long> votes = new LinkedHashMap<>();
            for (Candidate candidate : candidates) {
                if (states.get(candidate) != State.EXCLUDED) {
                    votes.put(candidate, 0L);
                }
            }
            for (Ballot ballot : ballots) {
                distribute(ballot.ranking(), 0, fixed.one(), ballot.count().longValueExact(), votes);
            }
            long held = 0;
            for (long value : votes.values()) {
                held += value;
            }
            long quota = fixed.divideDown(held, seats + 1) + fixed.epsilon();
            return new Distribution(votes, totalVotes - held, quota);
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
         */
        private void distribute(List<Set<Candidate>> ranking, int index, long weight, long multiplier,
                Map<Candidate, Long> votes) {
            for (int i = index; i < ranking.size() && weight > 0; i++) {
                List<Candidate> standing = ranking.get(i).stream().filter(this::isStanding).toList();
                if (standing.isEmpty()) {
                    continue;
                }
                long share = fixed.divideDown(weight, standing.size());
                for (Candidate candidate : standing) {
                    long keepFactor = keepFactors.get(candidate);
                    long kept = switch (keepRule) {
                        case MEEK -> fixed.multiply(share, keepFactor, false);
                        case WARREN -> Math.min(keepFactor, share);
                    };
                    long passedOn = switch (keepRule) {
                        case MEEK -> fixed.multiply(share, fixed.one() - keepFactor, false);
                        case WARREN -> share - kept;
                    };
                    votes.merge(candidate, Math.multiplyExact(kept, multiplier), Long::sum);
                    if (standing.size() > 1) {
                        distribute(ranking, i + 1, passedOn, multiplier, votes);
                    } else {
                        weight = passedOn;
                    }
                }
                if (standing.size() > 1) {
                    return;
                }
            }
        }

        private Candidate lowestHopeful(Distribution distribution, long surplus, TieBreakRule tieBreak,
                List<RoundRecord> priorRounds) {
            long lowest = hopefuls().stream().mapToLong(distribution.votes()::get).min().orElseThrow();
            List<Candidate> tied = hopefuls().stream()
                    .filter(candidate -> distribution.votes().get(candidate) <= lowest + surplus)
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
                if (states.get(candidate) == State.HOPEFUL || justElected.contains(candidate)) {
                    tallies.put(candidate, display(distribution.votes().get(candidate), ruleset));
                }
            }
            return tallies;
        }

        private Fraction display(long value, MeekRuleset ruleset) {
            return ruleset.roundingRule().apply(fixed.toFraction(value));
        }

        private boolean isStanding(Candidate candidate) {
            State state = states.get(candidate);
            return state == State.HOPEFUL || state == State.ELECTED;
        }

        private Set<Candidate> hopefuls() {
            Set<Candidate> result = new LinkedHashSet<>();
            for (Map.Entry<Candidate, State> entry : states.entrySet()) {
                if (entry.getValue() == State.HOPEFUL) {
                    result.add(entry.getKey());
                }
            }
            return result;
        }

        private int electedCount() {
            return (int) states.values().stream().filter(state -> state == State.ELECTED).count();
        }
    }
}
