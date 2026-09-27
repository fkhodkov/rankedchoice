package info.fkhodkov.rankedchoice.stv.meek;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
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
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;

/**
 * Runs an STV count by Meek's method (Hill, Wichmann &amp; Woodall, "Algorithm 123: Single
 * Transferable Vote by Meek's Method", <i>The Computer Journal</i> 30(3), 1987) — structurally
 * different from every {@link info.fkhodkov.rankedchoice.stv.transfer.TransferMethod} in this
 * project, so it isn't one: instead of tracking which single "parcel" a ballot most recently
 * arrived through and moving only the ballots touched by one election/elimination event, every
 * ballot's value is recomputed from scratch, every round, by walking its full ranking against
 * every candidate's current state.
 *
 * <p>Each candidate is hopeful (keeps 100% of anything it receives), elected (keeps a
 * fractional "keep factor" of anything it receives, passing the rest to the ballot's next
 * preference — recalculated every round so its own total tracks the quota as closely as the
 * convergence tolerance allows), or excluded (keeps 0%, passing everything on). A ballot's
 * value cascades down its ranking: each candidate in turn keeps {@code weight} of whatever
 * value reaches them and passes {@code 1 - weight} on, so a ballot ranking A, B, C with
 * elected-candidate weights a and b (C hopeful) gives A {@code a}, B {@code (1-a)*b}, and C the
 * remainder {@code (1-a)*(1-b)}.
 *
 * <p>Unlike quota-based STV, the quota itself changes every round — {@code (total votes -
 * total excess) / (seats + 1)}, where "excess" is the portion of ballot value that reaches the
 * end of a ranking with nobody left to receive it (this project's "exhausted votes", under a
 * different name in the source literature) — so an elected candidate's keep factor is only
 * ever an approximation, converged by repeatedly recomputing vote totals and nudging each
 * elected candidate's weight by {@code quota / theirTotal} until every elected candidate's
 * total is within 0.001% of the (recomputed) quota. This tolerance-based convergence, not a
 * single exact rational computation, is why this class works in {@code double} internally
 * rather than this project's exact {@link Fraction} type: Meek's own termination rule is a
 * numeric tolerance, so there is no exact value to converge to in the first place, and an
 * exact-fraction fixed-point iteration would blow up its own denominators without ever
 * actually finishing. Every figure is converted to a {@link Fraction} — via a fixed, generous
 * 12-decimal-place intermediate precision, then the ruleset's own {@code roundingRule} — only
 * when a round's snapshot is recorded, so the resulting {@link StvResult}, and every one of
 * this project's report exporters, is unaffected by the double-vs-Fraction distinction.
 *
 * <p>{@link StvResult#quota()} reports the quota as of the count's final round only — a known
 * simplification, since Meek's quota (unlike every other method here) genuinely changes every
 * round; each round's own quota is folded into that round's frozen tallies rather than
 * surfaced as a separate per-round figure.
 *
 * <p>A hopeful crossing quota is checked on <em>every</em> convergence iteration, not gated
 * behind the currently-elected candidates fully converging first — see {@link #runOneStage}
 * for why, and {@code MeekMapLibreGoverningBoard2025FixtureTest} for the real election that
 * exposed this as a real bug rather than a stylistic choice: an earlier version of this class
 * got the right eventual winners but visibly wrong intermediate keep factors compared to that
 * real count's own published per-round figures.
 *
 * <p>Candidates must already exclude any withdrawn from standing. Tied rankings and short
 * ballots are handled per the ruleset's policy (see {@link MeekRuleset}); under {@code split},
 * a tied rank shares whatever reaches it evenly between its standing candidates, following
 * droop's Meek rule (see {@link #distribute}).
 */
public final class MeekCounter {

    /** Rule 2.9's own convergence band: q/v_j must land in [0.99999, 1.00001] for every elected candidate. */
    private static final double CONVERGENCE_TOLERANCE = 0.00001;
    private static final int MAX_CONVERGENCE_ITERATIONS = 1000;
    private static final int INTERMEDIATE_DECIMAL_PLACES = 12;
    /** Guards tie detection on {@code double} totals against floating-point noise, not genuine near-ties. */
    private static final double TIE_EPSILON = 1e-6;

    private enum State { HOPEFUL, ELECTED, EXCLUDED }

    private MeekCounter() {
    }

    public static StvResult count(List<Candidate> candidates, List<Ballot> rawBallots, int seats, MeekRuleset ruleset) {
        validate(candidates, seats);
        BallotFormality.FormalBallots formal = BallotFormality.apply(
                rawBallots, ruleset.tiedRankings(), ruleset.minimumPreferences(), candidates.size());
        List<Ballot> ballots = formal.ballots();

        Map<Candidate, State> states = new LinkedHashMap<>();
        Map<Candidate, Double> weights = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            states.put(candidate, State.HOPEFUL);
        }

        double totalVotes = 0;
        for (Ballot ballot : ballots) {
            if (!ballot.isBlank()) {
                totalVotes += ballot.count().doubleValue();
            }
        }

        List<Candidate> elected = new ArrayList<>();
        List<RoundRecord> rounds = new ArrayList<>();
        Fraction lastQuota = Fraction.ZERO;
        int roundNumber = 0;

        while (elected.size() < seats) {
            roundNumber++;
            int remainingSeats = seats - elected.size();
            Set<Candidate> hopefuls = hopefuls(states);

            if (hopefuls.size() <= remainingSeats) {
                Convergence converged = runOneStage(candidates, ballots, states, weights, totalVotes, seats);
                List<RoundRecord> priorRounds = List.copyOf(rounds);
                Map<Candidate, Fraction> shortcutTallies =
                        toFractionTallies(states, converged.totals(), candidates, ruleset.roundingRule());
                Fraction shortcutExhausted = toFraction(converged.excess(), ruleset.roundingRule());
                for (Candidate candidate :
                        sortedByTallyDescending(hopefuls, converged.totals(), ruleset.tieBreak(), priorRounds)) {
                    elected.add(candidate);
                    states.put(candidate, State.ELECTED);
                    weights.put(candidate, 1.0);
                    rounds.add(new RoundRecord(roundNumber, shortcutTallies,
                            new RoundAction.Elected(candidate, Fraction.ZERO), shortcutExhausted));
                    roundNumber++;
                }
                lastQuota = toFraction(converged.quota(), ruleset.roundingRule());
                break;
            }

            Convergence converged = runOneStage(candidates, ballots, states, weights, totalVotes, seats);
            lastQuota = toFraction(converged.quota(), ruleset.roundingRule());
            List<RoundRecord> priorRounds = List.copyOf(rounds);
            Map<Candidate, Fraction> roundTallies =
                    toFractionTallies(states, converged.totals(), candidates, ruleset.roundingRule());
            Fraction roundExhausted = toFraction(converged.excess(), ruleset.roundingRule());

            List<Candidate> meetingQuota = hopefuls.stream()
                    .filter(candidate -> converged.totals().get(candidate) >= converged.quota())
                    .toList();

            if (!meetingQuota.isEmpty()) {
                List<Candidate> electedThisStage = sortedByTallyDescending(
                        new LinkedHashSet<>(meetingQuota), converged.totals(), ruleset.tieBreak(), priorRounds);
                if (electedThisStage.size() > remainingSeats) {
                    electedThisStage = electedThisStage.subList(0, remainingSeats);
                }
                for (int i = 0; i < electedThisStage.size(); i++) {
                    Candidate candidate = electedThisStage.get(i);
                    double candidateVotes = converged.totals().get(candidate);
                    Fraction surplus = toFraction(candidateVotes - converged.quota(), ruleset.roundingRule());
                    states.put(candidate, State.ELECTED);
                    weights.put(candidate, 1.0);
                    elected.add(candidate);
                    rounds.add(new RoundRecord(roundNumber, roundTallies,
                            new RoundAction.Elected(candidate, surplus.signum() > 0 ? surplus : Fraction.ZERO),
                            roundExhausted));
                    if (i < electedThisStage.size() - 1) {
                        roundNumber++;
                    }
                }
                continue;
            }

            double lowest = hopefuls.stream().mapToDouble(converged.totals()::get).min().orElseThrow();
            List<Candidate> tiedLowest = hopefuls.stream()
                    .filter(candidate -> Math.abs(converged.totals().get(candidate) - lowest) <= TIE_EPSILON)
                    .toList();
            Candidate toExclude = tiedLowest.size() == 1
                    ? tiedLowest.getFirst()
                    : ruleset.tieBreak().candidateToEliminate(tiedLowest, priorRounds);
            states.put(toExclude, State.EXCLUDED);
            rounds.add(new RoundRecord(roundNumber, roundTallies, new RoundAction.Eliminated(toExclude), roundExhausted));
        }

        return new StvResult(elected, rounds, lastQuota, formal.informal());
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

    private record Convergence(Map<Candidate, Double> totals, double excess, double quota) {
    }

    /**
     * Runs rule 2.9's inner loop -- recompute every candidate's vote total (rule 2.3) and the
     * quota (rule 2.5), then nudge every elected candidate's weight by {@code quota /
     * theirTotal} and go again -- but checks rule 2.6 (has any hopeful reached quota?) on
     * *every* iteration, not just once the elected candidates' weights have fully converged,
     * and returns the moment that happens. Only once a full pass finds nobody newly qualifying
     * *and* every elected candidate's weight has stopped moving (rule 2.9's own tolerance) does
     * this return "as converged as it's going to get" for the caller to fall back to rule 2.7
     * (exclude the lowest hopeful).
     *
     * <p>Checking rule 2.6 every iteration, rather than gating it behind full convergence,
     * matters because electing someone is never premature -- their vote total can only still be
     * correct or grow as other elected candidates' weights keep shrinking -- so there's no
     * reason to wait. A hopeful who qualifies mid-convergence is folded into the very next
     * weight-update alongside whoever was already elected, at their own starting weight of 1,
     * while everyone already elected keeps whatever weight this loop had already nudged them
     * to -- nobody's progress is thrown away and restarted. This matches real published
     * per-round keep factors from a real Meek STV election (see {@code
     * MeekMapLibreGoverningBoard2025FixtureTest}); an earlier version of this method converged
     * the existing elected set to full tolerance before ever checking for new qualifiers, which
     * gave the same eventual winners but visibly wrong (too converged, too fast) intermediate
     * keep-factor figures compared to that real count.
     */
    private static Convergence runOneStage(List<Candidate> candidates, List<Ballot> ballots,
            Map<Candidate, State> states, Map<Candidate, Double> weights, double totalVotes, int seats) {
        Map<Candidate, Double> totals = computeTotals(candidates, ballots, states, weights);
        double excess = excessOf(totalVotes, totals);
        double quota = (totalVotes - excess) / (seats + 1);

        for (int iteration = 0; iteration < MAX_CONVERGENCE_ITERATIONS; iteration++) {
            List<Candidate> electedCandidates = candidates.stream()
                    .filter(candidate -> states.get(candidate) == State.ELECTED)
                    .toList();
            Map<Candidate, Double> currentTotals = totals;
            double currentQuota = quota;
            boolean anyHopefulQualifies = candidates.stream().anyMatch(candidate ->
                    states.get(candidate) == State.HOPEFUL && currentTotals.get(candidate) >= currentQuota);
            if (anyHopefulQualifies || electedCandidates.isEmpty() || isConverged(electedCandidates, totals, quota)) {
                return new Convergence(totals, excess, quota);
            }
            for (Candidate candidate : electedCandidates) {
                weights.merge(candidate, quota / totals.get(candidate), (oldWeight, ratio) -> oldWeight * ratio);
            }
            totals = computeTotals(candidates, ballots, states, weights);
            excess = excessOf(totalVotes, totals);
            quota = (totalVotes - excess) / (seats + 1);
        }
        throw new IllegalStateException(
                "Meek's method failed to converge within " + MAX_CONVERGENCE_ITERATIONS + " iterations");
    }

    private static boolean isConverged(List<Candidate> electedCandidates, Map<Candidate, Double> totals, double quota) {
        for (Candidate candidate : electedCandidates) {
            double ratio = quota / totals.get(candidate);
            if (ratio < 1 - CONVERGENCE_TOLERANCE || ratio > 1 + CONVERGENCE_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    private static double excessOf(double totalVotes, Map<Candidate, Double> totals) {
        double assigned = 0;
        for (double value : totals.values()) {
            assigned += value;
        }
        // Floating-point noise can push this a hair below zero when every ballot is fully
        // assigned; excess is never negative in theory, so clamp rather than let a
        // near-zero-but-negative value distort the quota or an exported figure.
        return Math.max(0, totalVotes - assigned);
    }

    /**
     * Every hopeful and elected candidate's current vote total (rule 2.3/2.4): each ballot's
     * value cascades down its ranking, with every candidate along the way keeping {@code
     * weight} of whatever reaches them (1 for hopeful, 0 for excluded, the candidate's own
     * converging keep factor for elected) and passing the remainder to the next preference.
     * Excluded candidates get no entry, matching {@link
     * info.fkhodkov.rankedchoice.stv.StvCounter}'s "only continuing candidates" convention.
     */
    private static Map<Candidate, Double> computeTotals(
            List<Candidate> candidates, List<Ballot> ballots, Map<Candidate, State> states, Map<Candidate, Double> weights) {
        Map<Candidate, Double> totals = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (states.get(candidate) != State.EXCLUDED) {
                totals.put(candidate, 0.0);
            }
        }
        for (Ballot ballot : ballots) {
            distribute(ballot.ranking(), 0, ballot.count().doubleValue(), candidates, states, weights, totals);
        }
        return totals;
    }

    /**
     * Cascades {@code value} down {@code ranking} from {@code index} (see {@link
     * #computeTotals}). A tied rank splits whatever reaches it evenly between its
     * still-standing candidates; each keeps its own share by its keep factor, and what each one
     * doesn't keep flows on to the next rank separately — the equal-preference distribution in
     * droop's Meek rule ({@code jklundell/droop}, {@code rules/meek.py}, {@code
     * distributeVotes}). One deliberate difference: droop routes any ballot containing a tie
     * through a separate path that stops outright at the first rank with no standing
     * candidate — tied or not — losing the rest of the ballot's value, whereas its path for
     * untied ballots skips excluded candidates and carries on. This carries on in both cases.
     * Verified on synthetic elections with hundreds of tied rankings: every round differs from
     * stock droop only after that situation arises, and matches a copy of droop with just that
     * one path patched to carry on, to within this class's own convergence tolerance.
     */
    private static void distribute(List<Set<Candidate>> ranking, int index, double value, List<Candidate> candidates,
            Map<Candidate, State> states, Map<Candidate, Double> weights, Map<Candidate, Double> totals) {
        for (int i = index; i < ranking.size() && value > 0; i++) {
            Set<Candidate> rank = ranking.get(i);
            // Tied members are taken in declared candidate order, so floating-point summation
            // order doesn't depend on Set iteration order (which varies between JVM runs).
            List<Candidate> standing = rank.size() == 1
                    ? rank.stream().filter(candidate -> isStanding(states.get(candidate))).toList()
                    : candidates.stream().filter(rank::contains)
                            .filter(candidate -> isStanding(states.get(candidate))).toList();
            if (standing.isEmpty()) {
                continue;
            }
            if (standing.size() == 1) {
                Candidate candidate = standing.getFirst();
                double kept = states.get(candidate) == State.HOPEFUL ? value : value * weights.get(candidate);
                totals.merge(candidate, kept, Double::sum);
                value -= kept;
                continue;
            }
            double share = value / standing.size();
            for (Candidate candidate : standing) {
                double kept = states.get(candidate) == State.HOPEFUL ? share : share * weights.get(candidate);
                totals.merge(candidate, kept, Double::sum);
                distribute(ranking, i + 1, share - kept, candidates, states, weights, totals);
            }
            return;
        }
    }

    /** Hopeful or elected; a candidate this count doesn't know about (e.g. withdrawn) never is. */
    private static boolean isStanding(State state) {
        return state == State.HOPEFUL || state == State.ELECTED;
    }

    private static Set<Candidate> hopefuls(Map<Candidate, State> states) {
        Set<Candidate> result = new LinkedHashSet<>();
        for (Map.Entry<Candidate, State> entry : states.entrySet()) {
            if (entry.getValue() == State.HOPEFUL) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private static Candidate pickHighest(List<Candidate> candidates, Map<Candidate, Double> totals,
            TieBreakRule tieBreak, List<RoundRecord> priorRounds) {
        double highest = candidates.stream().mapToDouble(totals::get).max().orElseThrow();
        List<Candidate> tiedHighest = candidates.stream()
                .filter(candidate -> Math.abs(totals.get(candidate) - highest) <= TIE_EPSILON)
                .toList();
        return tiedHighest.size() == 1 ? tiedHighest.getFirst() : tieBreak.candidateToElectFirst(tiedHighest, priorRounds);
    }

    private static List<Candidate> sortedByTallyDescending(Set<Candidate> candidates, Map<Candidate, Double> totals,
            TieBreakRule tieBreak, List<RoundRecord> priorRounds) {
        List<Candidate> remaining = new ArrayList<>(candidates);
        List<Candidate> result = new ArrayList<>(remaining.size());
        while (!remaining.isEmpty()) {
            Candidate top = pickHighest(remaining, totals, tieBreak, priorRounds);
            result.add(top);
            remaining.remove(top);
        }
        return result;
    }

    /** Only hopeful candidates are shown, in declared order — matches {@code StvCounter}'s display convention. */
    private static Map<Candidate, Fraction> toFractionTallies(Map<Candidate, State> states,
            Map<Candidate, Double> totals, List<Candidate> candidates, RoundingRule roundingRule) {
        Map<Candidate, Fraction> tallies = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (states.get(candidate) == State.HOPEFUL) {
                tallies.put(candidate, toFraction(totals.get(candidate), roundingRule));
            }
        }
        return tallies;
    }

    private static Fraction toFraction(double value, RoundingRule roundingRule) {
        BigDecimal decimal = BigDecimal.valueOf(value).setScale(INTERMEDIATE_DECIMAL_PLACES, RoundingMode.HALF_UP);
        Fraction exact = Fraction.of(decimal.unscaledValue(), BigInteger.TEN.pow(INTERMEDIATE_DECIMAL_PLACES));
        return roundingRule.apply(exact);
    }
}
