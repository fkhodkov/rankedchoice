package info.fkhodkov.rankedchoice.stv;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.HeldParcel;
import info.fkhodkov.rankedchoice.stv.transfer.LastParcelDeferredRoundingTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.LastParcelTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.TransferMethod;
import info.fkhodkov.rankedchoice.stv.transfer.TruncatedFractionalTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.UnweightedExclusiveTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.UnweightedInclusiveTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.WeightedInclusiveTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.WeightedTransferableTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.WigmTransfer;

/**
 * Runs a single transferable vote count. Each round either elects the candidate(s) meeting
 * quota (transferring their surplus onward) or eliminates the lowest continuing candidate
 * (transferring all their votes onward), until every seat is filled — including the standard
 * shortcut where, once exactly as many candidates remain continuing as there are seats left,
 * they are all declared elected without further transfers.
 *
 * <p>Every ballot tracks which single transfer event it most recently arrived through (its
 * "parcel"), so a last-parcel-only transfer method can tell which of a candidate's held
 * ballots arrived last, as distinct from an inclusive method that doesn't care.
 *
 * <p>Last-parcel-family rulesets ({@link LastParcelTransfer}, {@link
 * LastParcelDeferredRoundingTransfer} — see {@link #defersSurplusTransfers}) don't transfer a
 * surplus the moment a candidate qualifies: a candidate who crosses quota is immediately
 * frozen (removed from {@code continuing}, added to {@code elected}) and their surplus waits
 * for a later round, largest first. Under ERS97's {@code deferSurpluses} it can wait for many
 * rounds, while exclusions go ahead, as long as it couldn't change who is excluded. This also
 * means eliminating a candidate under these rulesets processes their held ballots one value-group at
 * a time (largest value first, per ERS97's "exclusion by value"), re-checking for newly
 * quota-crossing candidates after each group — a candidate who qualifies partway through
 * stops receiving that same elimination's remaining, lower-valued groups entirely, rather than
 * being credited with votes a real ERS97 count would have withheld from them. WIGM/fractional
 * rulesets don't do any of this — confirmed correct via real Scottish election data using the
 * simpler "transfer everything for this event immediately" model already in place.
 *
 * <p>Candidates must already exclude any withdrawn from standing. Tied rankings (a rank with
 * more than one candidate) are handled per the ruleset's {@link TiedRankingPolicy}: rejected,
 * truncated or skipped as a preprocessing step before the count starts, or genuinely split.
 * Splitting is only defined under {@link
 * info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer}, since WIGM and the last-parcel
 * family have no equal-preference splitting rule. When splitting, a ballot's current holder
 * is really a <em>group</em> — every still-continuing
 * candidate sharing its front-most remaining rank (see {@link #assignNextHolder}) — and the
 * ballot's pooled value splits evenly across the group every round. This isn't a one-time
 * split into independent sub-ballots: verified by reading the source of CRAN's {@code vote}
 * package (Sevcikova, Silverman, Raftery — <a
 * href="https://cran.r-project.org/package=vote">cran.r-project.org/package=vote</a>, an
 * actively maintained, real equal-preference STV implementation) directly, the pool
 * re-splits among whichever tied candidates remain each round; when one of several tied
 * candidates is elected, only their own share is scaled by the surplus ratio, and the
 * others' shares fold back into the pool unscaled; when one is eliminated instead, the
 * ballot's value is untouched and whoever else remains at that rank simply inherits the
 * <em>entire</em> pool next round — not just the share they'd been individually credited
 * with while the tie lasted.
 */
public final class StvCounter {

    private StvCounter() {
    }

    /**
     * Ballot values whose denominators grow past this many bits (about 77 decimal digits) under
     * {@link Exact} rounding mean the count is compounding exact transfer ratios, ballot after
     * ballot, into ever-larger fractions: ordinary exact counts stay far below it, and past it the
     * count can slow from seconds to hours (see {@link #count(List, List, int, StvRuleset,
     * Consumer)}).
     */
    static final int EXACT_GROWTH_WARNING_BITS = 256;

    public static StvResult count(List<Candidate> candidates, List<Ballot> ballots, int seats, StvRuleset ruleset) {
        return count(candidates, ballots, seats, ruleset, _ -> { });
    }

    /**
     * As {@link #count(List, List, int, StvRuleset)}, reporting problems worth knowing about
     * while the count is still running to {@code warnings}. At present there's one: under {@link
     * Exact} rounding, fractional transfers compound into ballot values with ever-larger
     * denominators when ballots pass through many elected candidates -- long rankings, many
     * seats -- and a large count can then take hours rather than seconds. As soon as any ballot's
     * value passes {@link #EXACT_GROWTH_WARNING_BITS}, this says so, once, so a caller can suggest
     * a truncating rounding rule instead of leaving someone waiting. Nothing about the count
     * itself changes.
     */
    public static StvResult count(List<Candidate> candidates, List<Ballot> ballots, int seats, StvRuleset ruleset,
            Consumer<String> warnings) {
        StvCounterValidation.validate(candidates, seats);
        ruleset.tiedRankings().requireSplitDefinedFor(ruleset.transferMethod());
        BallotFormality.FormalBallots formal = BallotFormality.apply(
                ballots, ruleset.tiedRankings(), ruleset.minimumPreferences(), candidates.size());
        List<Ballot> countedBallots = formal.ballots();

        Set<Candidate> continuing = new LinkedHashSet<>(candidates);
        List<BallotState> states = new ArrayList<>(countedBallots.size());
        BigInteger totalValidVotes = BigInteger.ZERO;
        // The quota is on the votes the first count gives a candidate: a ballot ranking only
        // withdrawn candidates exhausts there and doesn't count towards it (the NSW Electoral
        // Commission's counts, when an elected mayor leaves the councillor count).
        BigInteger firstPreferenceVotes = BigInteger.ZERO;
        for (Ballot ballot : countedBallots) {
            if (ballot.isBlank()) {
                continue;
            }
            totalValidVotes = totalValidVotes.add(ballot.count());
            BallotState state = new BallotState(ballot);
            assignNextHolder(state, continuing);
            if (!state.holders.isEmpty()) {
                firstPreferenceVotes = firstPreferenceVotes.add(ballot.count());
            }
            states.add(state);
        }

        Fraction quota = ruleset.quota().compute(firstPreferenceVotes, seats);
        boolean defers = defersSurplusTransfers(ruleset.transferMethod());
        if (defers && ruleset.mathematicalElimination()) {
            throw new IllegalArgumentException(
                    "mathematicalElimination needs a transfer method that moves a surplus at once");
        }
        // The ACT rounds each candidate's gain from each transfer step, not their running total.
        RunningTallies running = RunningTallies.of(states,
                ruleset.transferMethod().roundsGainsPerStep() ? ruleset.roundingRule() : null);

        List<Candidate> elected = new ArrayList<>();
        List<RoundRecord> rounds = new ArrayList<>();
        // What a tie-break looks back through: every round, and within an exclusion that moves in
        // groups, the tallies after each group, each a count of its own (the AEC's, the ACT's,
        // Victoria's s114A(28)).
        List<RoundRecord> history = new ArrayList<>();
        Consumer<RoundRecord> record = round -> {
            rounds.add(round);
            history.add(round);
        };
        // Candidates who have crossed quota but whose surplus hasn't been transferred yet —
        // only ever populated when `defers` is true. Processed one at a time, largest first.
        Map<Candidate, Fraction> pendingSurplus = new LinkedHashMap<>();
        Credits credits = new Credits(Fraction.of(totalValidVotes), elected, pendingSurplus, running,
                ruleset.roundingRule());
        int roundNumber = 0;
        ParcelIds parcelIds = new ParcelIds();

        boolean watchExactGrowth = ruleset.roundingRule() instanceof Exact;
        while (elected.size() < seats) {
            roundNumber++;
            int remainingSeats = seats - elected.size();
            if (watchExactGrowth) {
                int bits = largestDenominatorBits(states);
                if (bits > EXACT_GROWTH_WARNING_BITS) {
                    warnings.accept("exact arithmetic has produced ballot values with " + (int) (bits * Math.log10(2))
                            + "-digit denominators by round " + roundNumber + "; with this many transfers the count"
                            + " may become very slow, and a truncating rounding rule would keep values bounded");
                    watchExactGrowth = false;
                }
            }

            if (continuing.size() <= remainingSeats) {
                Map<Candidate, Fraction> tallies = running.tallies(continuing, ruleset.roundingRule());
                Fraction exhausted = running.exhausted();
                Fraction loss = credits.roundingLoss(tallies, exhausted);
                List<RoundRecord> priorRounds = List.copyOf(history);
                for (Candidate candidate
                        : sortedByTallyDescending(continuing, tallies, ruleset.tieBreak(), priorRounds)) {
                    elected.add(candidate);
                    continuing.remove(candidate);
                    credits.settle(candidate, tallies.get(candidate));
                    record.accept(new RoundRecord(
                            roundNumber, tallies, new RoundAction.Elected(candidate, Fraction.ZERO), exhausted, loss));
                    roundNumber++;
                }
                drainPendingSurplus(running, continuing, pendingSurplus, credits, ruleset.roundingRule(), rounds,
                        roundNumber);
                break;
            }

            Map<Candidate, Fraction> tallies = running.tallies(continuing, ruleset.roundingRule());
            Fraction exhausted = running.exhausted();
            Fraction loss = credits.roundingLoss(tallies, exhausted);
            List<RoundRecord> priorRounds = List.copyOf(history);
            Fraction activeVotes = positiveSurplusTotal(pendingSurplus, quota);
            for (Fraction tally : tallies.values()) {
                activeVotes = activeVotes.add(tally);
            }
            if (ruleset.electAsTheyStand() && remainingSeats == 1 && continuing.size() == 2
                    && pendingSurplus.isEmpty()) {
                // s273(17): the last seat goes to the higher of the last two, quota or not.
                Candidate higher = pickHighest(new ArrayList<>(continuing), tallies, ruleset.tieBreak(), priorRounds);
                elected.add(higher);
                continuing.remove(higher);
                credits.settle(higher, tallies.get(higher));
                record.accept(new RoundRecord(
                        roundNumber, tallies, new RoundAction.Elected(higher, Fraction.ZERO), exhausted, loss));
                break;
            }
            // Minneapolis defeats everyone who can no longer win before any surplus moves.
            List<Candidate> hopeless = ruleset.mathematicalElimination()
                    ? mathematicallyEliminated(states, continuing, tallies, quota, remainingSeats)
                    : List.of();
            Fraction required = votesRequired(ruleset, activeVotes, remainingSeats);
            List<Candidate> meetingQuota = continuing.stream()
                    .filter(candidate -> elects(tallies.get(candidate), quota, required))
                    .toList();

            List<Candidate> sure = ruleset.electSureWinners() && meetingQuota.isEmpty() && hopeless.isEmpty()
                    ? sureWinners(continuing, tallies, remainingSeats, positiveSurplusTotal(pendingSurplus, quota),
                            ruleset.tieBreak(), priorRounds)
                    : List.of();
            if (!sure.isEmpty()) {
                for (Candidate candidate : sure) {
                    elected.add(candidate);
                    continuing.remove(candidate);
                    credits.settle(candidate, tallies.get(candidate));
                    record.accept(new RoundRecord(
                            roundNumber, tallies, new RoundAction.Elected(candidate, Fraction.ZERO), exhausted, loss));
                    roundNumber++;
                }
                break;
            }
            if (hopeless.isEmpty() && !meetingQuota.isEmpty()) {
                // Every candidate who meets quota in the same tally snapshot is declared
                // elected as a batch, before any of their surpluses are transferred — so none
                // of them can still receive (and thus inflate their own eventual surplus with)
                // a fellow batch member's transfer.
                List<Candidate> electedThisStage = ruleset.votesRequired()
                        ? electOneByOne(continuing, tallies, quota, positiveSurplusTotal(pendingSurplus, quota),
                                remainingSeats, ruleset, priorRounds)
                        : sortedByTallyDescending(
                                new LinkedHashSet<>(meetingQuota), tallies, ruleset.tieBreak(), priorRounds);
                // Minneapolis moves one surplus a round, the largest; the rest wait, still continuing.
                int electing = ruleset.mathematicalElimination() ? 1 : remainingSeats;
                if (electedThisStage.size() > electing) {
                    electedThisStage = electedThisStage.subList(0, electing);
                }
                Map<Candidate, Fraction> frozenVotes = new LinkedHashMap<>();
                for (Candidate candidate : electedThisStage) {
                    frozenVotes.put(candidate, tallies.get(candidate));
                    elected.add(candidate);
                    continuing.remove(candidate);
                    if (!defers) {
                        credits.settle(candidate, tallies.get(candidate)); // until their surplus moves
                    }
                }

                if (!defers) {
                    // Surpluses are applied immediately, one at a time, largest first —
                    // confirmed correct for WIGM/fractional against real Scottish election data.
                    // A transfer that takes someone to quota elects them there and then, before
                    // the next surplus moves, so its ballots pass them by (SSI 2007/42 rules 46
                    // and 48; North Lanarkshire's Coatbridge South count in 2017 turns on it).
                    List<Candidate> queue = new ArrayList<>(electedThisStage);
                    boolean firstOfStage = true;
                    while (!queue.isEmpty()) {
                        Candidate candidate = queue.removeFirst();
                        Fraction candidateVotes = frozenVotes.get(candidate);
                        if (ruleset.electSureWinners() && elected.size() < seats) {
                            // Leaders no surplus still to move could overtake are elected first.
                            Fraction waiting = max(candidateVotes.subtract(quota), Fraction.ZERO);
                            for (Candidate next : queue) {
                                waiting = waiting.add(max(frozenVotes.get(next).subtract(quota), Fraction.ZERO));
                            }
                            Map<Candidate, Fraction> now = running.tallies(continuing, ruleset.roundingRule());
                            for (Candidate winner : sureWinners(continuing, now, seats - elected.size(), waiting,
                                    ruleset.tieBreak(), List.copyOf(history))) {
                                frozenVotes.put(winner, now.get(winner));
                                elected.add(winner);
                                continuing.remove(winner);
                                credits.settle(winner, now.get(winner));
                                queue.add(winner);
                            }
                        }
                        // Once every seat is filled the count ends: no further surplus moves.
                        Fraction surplus = elected.size() < seats ? candidateVotes.subtract(quota) : Fraction.ZERO;
                        Map<Candidate, Fraction> roundTallies = tallies;
                        Fraction roundExhausted = exhausted;
                        Fraction roundLoss = loss;
                        if (!firstOfStage) {
                            roundTallies = running.tallies(continuing, ruleset.roundingRule());
                            roundTallies.put(candidate, candidateVotes);
                            roundExhausted = running.exhausted();
                            roundLoss = credits.roundingLoss(roundTallies, roundExhausted);
                        }
                        firstOfStage = false;
                        // Ties among those this transfer elects look back from the votes just before it.
                        List<RoundRecord> lookBack = new ArrayList<>(history);
                        lookBack.add(new RoundRecord(roundNumber, roundTallies,
                                new RoundAction.Elected(candidate, surplus), roundExhausted, roundLoss));
                        if (surplus.signum() > 0 && !continuing.isEmpty()) {
                            transferSurplus(states, running, candidate, surplus, candidateVotes,
                                    ruleset.transferMethod(), ruleset.roundingRule(), continuing, parcelIds);
                            credits.settle(candidate, quota);
                            // Under simultaneousSurpluses a candidate this takes to quota waits for the
                            // next round to be elected, so the round's other surpluses can reach them.
                            if (elected.size() < seats && !ruleset.simultaneousSurpluses()
                                    && !ruleset.mathematicalElimination()) {
                                Map<Candidate, Fraction> fresh = running.tallies(continuing, ruleset.roundingRule());
                                List<Candidate> reached = continuing.stream()
                                        .filter(other -> fresh.get(other).compareTo(quota) >= 0)
                                        .toList();
                                if (!reached.isEmpty()) {
                                    List<Candidate> newly = sortedByTallyDescending(
                                            new LinkedHashSet<>(reached), fresh, ruleset.tieBreak(), lookBack);
                                    newly = newly.subList(0, Math.min(newly.size(), seats - elected.size()));
                                    for (Candidate other : newly) {
                                        frozenVotes.put(other, fresh.get(other));
                                        elected.add(other);
                                        continuing.remove(other);
                                        credits.settle(other, fresh.get(other));
                                    }
                                    queue.addAll(newly);
                                    if (!ruleset.surplusesInElectionOrder()) {
                                        queue = new ArrayList<>(sortedByTallyDescending(new LinkedHashSet<>(queue),
                                                frozenVotes, ruleset.tieBreak(), lookBack));
                                    }
                                }
                            }
                        }
                        record.accept(new RoundRecord(roundNumber, roundTallies,
                                new RoundAction.Elected(candidate, surplus), roundExhausted, roundLoss));
                        if (!queue.isEmpty()) {
                            roundNumber++;
                        }
                    }
                    continue;
                }

                // Deferred instead: freeze them all now, but don't transfer any surplus yet --
                // fall through below to pop whichever pending surplus (possibly one of these,
                // possibly an older one) is currently largest.
                pendingSurplus.putAll(frozenVotes);
            }
            if (defers && elected.size() >= seats) {
                break; // every seat filled: the count ends, pending surpluses untransferred
            }

            boolean deferred = ruleset.deferSurpluses() && !pendingSurplus.isEmpty()
                    && canDeferSurpluses(continuing, tallies, pendingSurplus, quota, seats - elected.size(),
                            ruleset.batchExclusion());
            if (defers && !pendingSurplus.isEmpty() && !deferred) {
                // pendingSurplus keeps election order, so its first is the earliest elected.
                Candidate candidate = ruleset.surplusesInElectionOrder()
                        ? pendingSurplus.keySet().iterator().next()
                        : pickHighest(new ArrayList<>(pendingSurplus.keySet()), pendingSurplus, ruleset.tieBreak(),
                                priorRounds);
                Fraction frozenTally = pendingSurplus.remove(candidate);
                // Elected on ERS97's votes required, below quota, there's no surplus.
                Fraction surplus = max(frozenTally.subtract(quota), Fraction.ZERO);
                // `tallies` and `exhausted` were captured before any of this round's actions
                // (including the freeze just above, if any) -- exactly the "before this
                // round's action" state the RoundRecord needs, so read them now, before the
                // transfer below mutates ballot state.
                Map<Candidate, Fraction> roundTallies = new LinkedHashMap<>(tallies);
                roundTallies.put(candidate, frozenTally);
                credits.settle(candidate, frozenTally);
                if (surplus.signum() > 0 && !continuing.isEmpty()) {
                    transferSurplus(states, running, candidate, surplus, frozenTally, ruleset.transferMethod(),
                            ruleset.roundingRule(), continuing, parcelIds);
                    credits.settle(candidate, quota);
                }
                record.accept(new RoundRecord(
                        roundNumber, roundTallies, new RoundAction.Elected(candidate, surplus), exhausted, loss));
                continue;
            }

            List<RoundRecord> subCounts = new ArrayList<>();
            List<Candidate> toExclude = !hopeless.isEmpty() ? hopeless
                    : selectCandidatesToExclude(continuing, tallies, pendingSurplus, quota, seats - elected.size(),
                            defers && ruleset.batchExclusion(), ruleset.tieBreak(), priorRounds);
            for (Candidate candidate : toExclude) {
                continuing.remove(candidate);
            }
            if (ruleset.electAsTheyStand() && continuing.size() <= seats - elected.size()) {
                // s273(18): the rest are elected as they stand; the excluded candidate's papers stay put.
            } else if (defers) {
                parcelIds.next = eliminateByValueGroup(states, running, toExclude, continuing, elected,
                        pendingSurplus, quota, seats, ruleset, priorRounds, parcelIds.next, subCounts);
            } else {
                for (Candidate candidate : toExclude) {
                    transferEliminatedBallots(states, running, candidate, continuing,
                            ruleset.transferMethod().roundsGainsPerParcel(), parcelIds);
                }
            }
            for (int i = 0; i < toExclude.size(); i++) {
                record.accept(new RoundRecord(
                        roundNumber, tallies, new RoundAction.Eliminated(toExclude.get(i)), exhausted, loss));
                if (i < toExclude.size() - 1) {
                    roundNumber++;
                }
            }
            history.addAll(subCounts);
        }

        drainPendingSurplus(running, continuing, pendingSurplus, credits, ruleset.roundingRule(), rounds, roundNumber);

        return new StvResult(elected, rounds, quota, formal.informal());
    }

    /**
     * What each elected candidate is credited with, for {@link RoundRecord#roundingLoss}: their
     * frozen tally while their surplus waits ({@code pendingSurplus}), or until it moves, then
     * the quota; their tally if it never moves.
     */
    private static final class Credits {
        private final Fraction totalValidVotes;
        private final List<Candidate> elected;
        private final Map<Candidate, Fraction> pendingSurplus;
        private final Map<Candidate, Fraction> settled = new LinkedHashMap<>();
        private final RunningTallies running;
        private final RoundingRule roundingRule;

        Credits(Fraction totalValidVotes, List<Candidate> elected, Map<Candidate, Fraction> pendingSurplus,
                RunningTallies running, RoundingRule roundingRule) {
            this.totalValidVotes = totalValidVotes;
            this.elected = elected;
            this.pendingSurplus = pendingSurplus;
            this.running = running;
            this.roundingRule = roundingRule;
        }

        void settle(Candidate candidate, Fraction votes) {
            settled.put(candidate, votes);
        }

        /**
         * The valid votes not in {@code tallies}, credited to an elected candidate, exhausted, or
         * still held by an excluded candidate (whose exclusion stopped once every seat was filled).
         */
        Fraction roundingLoss(Map<Candidate, Fraction> tallies, Fraction exhausted) {
            Fraction accounted = exhausted.add(running.heldOutside(tallies.keySet(), elected, roundingRule));
            for (Fraction tally : tallies.values()) {
                accounted = accounted.add(tally);
            }
            for (Candidate candidate : elected) {
                if (!tallies.containsKey(candidate)) {
                    Fraction pending = pendingSurplus.get(candidate);
                    accounted = accounted.add(pending != null ? pending : settled.get(candidate));
                }
            }
            return totalValidVotes.subtract(accounted);
        }
    }

    /**
     * Whether this ruleset's transfer method wants surplus transfers deferred (and
     * eliminations processed value-group by value-group) rather than performed immediately —
     * see the class-level javadoc.
     */
    /**
     * The leading continuing candidates for the {@code remainingSeats}, if none of the rest can
     * overtake them: the last of them has more votes than all the others together plus {@code
     * waiting}, the surpluses still to move. Empty otherwise.
     */
    private static List<Candidate> sureWinners(Set<Candidate> continuing, Map<Candidate, Fraction> tallies,
            int remainingSeats, Fraction waiting, TieBreakRule tieBreak, List<RoundRecord> priorRounds) {
        List<Candidate> ranked = sortedByTallyDescending(continuing, tallies, tieBreak, priorRounds);
        if (remainingSeats <= 0 || ranked.size() <= remainingSeats) {
            return List.of();
        }
        Fraction trailing = waiting;
        for (Candidate candidate : ranked.subList(remainingSeats, ranked.size())) {
            trailing = trailing.add(tallies.get(candidate));
        }
        List<Candidate> leaders = ranked.subList(0, remainingSeats);
        return tallies.get(leaders.getLast()).compareTo(trailing) > 0 ? List.copyOf(leaders) : List.of();
    }

    private static boolean defersSurplusTransfers(TransferMethod transferMethod) {
        return switch (transferMethod) {
            case LastParcelTransfer _, LastParcelDeferredRoundingTransfer _, UnweightedInclusiveTransfer _,
                 UnweightedExclusiveTransfer _, WeightedInclusiveTransfer _ -> true;
            case FractionalTransfer _, WigmTransfer _, WeightedTransferableTransfer _, TruncatedFractionalTransfer _ ->
                false;
        };
    }

    /**
     * Emits a final, no-further-transfer {@link RoundAction.Elected} record (surplus {@link
     * Fraction#ZERO}, same convention as the "remaining continuing == remaining seats"
     * shortcut) for every candidate still awaiting their deferred surplus transfer once the
     * election has otherwise concluded — every seat is filled, so it can no longer matter.
     * Each gets its own sequential round number after {@code roundNumber}, same as the
     * shortcut's own bulk-election loop.
     */
    private static void drainPendingSurplus(RunningTallies running, Set<Candidate> continuing,
            Map<Candidate, Fraction> pendingSurplus, Credits credits, RoundingRule roundingRule,
            List<RoundRecord> rounds, int roundNumber) {
        if (pendingSurplus.isEmpty()) {
            return;
        }
        Map<Candidate, Fraction> tallies = running.tallies(continuing, roundingRule);
        Fraction exhausted = running.exhausted();
        Fraction loss = credits.roundingLoss(tallies, exhausted);
        int number = roundNumber;
        for (Map.Entry<Candidate, Fraction> entry : pendingSurplus.entrySet()) {
            Map<Candidate, Fraction> roundTallies = new LinkedHashMap<>(tallies);
            roundTallies.put(entry.getKey(), entry.getValue());
            credits.settle(entry.getKey(), entry.getValue());
            rounds.add(new RoundRecord(
                    number, roundTallies, new RoundAction.Elected(entry.getKey(), Fraction.ZERO), exhausted, loss));
            number++;
        }
        // So a second, unconditional call after the shortcut branch's `break` (there to catch
        // any surplus that never got frozen at all) doesn't re-emit these same candidates.
        pendingSurplus.clear();
    }

    /**
     * Eliminates every candidate in {@code from} (already removed from {@code continuing} by
     * the caller) as a single combined stage — pooling all of their held ballots together and
     * transferring them one value-group at a time, largest value first, per ERS97's "exclusion
     * by value" — instead of all at once. A single candidate is just the degenerate case; ERS97
     * rule 5.2.5's batch-exclusion pools several simultaneously-excluded candidates' ballots
     * this same way, exactly as a real, independent implementation (OpenSTV) demonstrated.
     * Re-checks for newly quota-crossing candidates after each pooled group and freezes them
     * into {@code pendingSurplus} immediately, so a candidate who qualifies partway through
     * stops receiving any of the remaining, lower-valued groups. Returns the next available
     * parcel id (one is consumed per value-group).
     */
    private static int eliminateByValueGroup(List<BallotState> states, RunningTallies running, List<Candidate> from,
            Set<Candidate> continuing, List<Candidate> elected, Map<Candidate, Fraction> pendingSurplus,
            Fraction quota, int seats, StvRuleset ruleset, List<RoundRecord> priorRounds, int nextParcelId,
            List<RoundRecord> subCounts) {
        Set<Candidate> excluded = new LinkedHashSet<>(from);
        // Groups, in the order they move: by value (highest first), first preferences then by
        // value, or by the parcel the papers arrived in (first preferences are parcel 0).
        ExclusionOrder order = ruleset.exclusionOrder();
        Comparator<ExclusionGroup> groupOrder = order == ExclusionOrder.PARCEL
                ? Comparator.comparingInt(ExclusionGroup::parcel)
                : Comparator.comparing(ExclusionGroup::laterThanFirstPreferences)
                        .thenComparing(ExclusionGroup::value, Comparator.reverseOrder());
        Map<ExclusionGroup, List<BallotState>> groups = new TreeMap<>(groupOrder);
        for (BallotState state : states) {
            if (!Collections.disjoint(state.holders, excluded)) {
                ExclusionGroup key = switch (order) {
                    case VALUE -> new ExclusionGroup(false, state.value, 0);
                    case FIRST_PREFERENCES_THEN_VALUE -> new ExclusionGroup(state.parcelId != 0, state.value, 0);
                    case PARCEL -> new ExclusionGroup(false, Fraction.ZERO, state.parcelId);
                };
                groups.computeIfAbsent(key, _ -> new ArrayList<>()).add(state);
            }
        }

        int parcelId = nextParcelId;
        for (List<BallotState> group : groups.values()) {
            int thisGroupParcelId = parcelId++;
            for (BallotState state : group) {
                running.remove(state);
                state.parcelId = thisGroupParcelId;
                assignNextHolder(state, continuing);
                running.add(state);
            }
            running.endStep();
            // Each group is a count of its own, for a tie-break to look back through.
            subCounts.add(new RoundRecord(0, running.tallies(continuing, ruleset.roundingRule()),
                    new RoundAction.Eliminated(from.getFirst()), running.exhausted(), Fraction.ZERO));

            int remainingSeats = seats - elected.size();
            if (remainingSeats <= 0) {
                continue;
            }
            Map<Candidate, Fraction> freshTallies = running.tallies(continuing, ruleset.roundingRule());
            Fraction required = null;
            if (ruleset.votesRequired()) {
                // Active: continuing candidates, what the excluded still hold, pending surpluses.
                Fraction activeVotes = running.heldOutside(continuing, elected, ruleset.roundingRule())
                        .add(positiveSurplusTotal(pendingSurplus, quota));
                for (Fraction tally : freshTallies.values()) {
                    activeVotes = activeVotes.add(tally);
                }
                required = votesRequired(ruleset, activeVotes, remainingSeats);
            }
            Fraction threshold = required;
            List<Candidate> newlyQualified = continuing.stream()
                    .filter(candidate -> elects(freshTallies.get(candidate), quota, threshold))
                    .toList();
            if (newlyQualified.isEmpty()) {
                continue;
            }
            List<Candidate> ordered = ruleset.votesRequired()
                    ? electOneByOne(continuing, freshTallies, quota,
                            running.heldOutside(continuing, elected, ruleset.roundingRule())
                                    .add(positiveSurplusTotal(pendingSurplus, quota)),
                            remainingSeats, ruleset, priorRounds)
                    : sortedByTallyDescending(
                            new LinkedHashSet<>(newlyQualified), freshTallies, ruleset.tieBreak(), priorRounds);
            if (ordered.size() > remainingSeats) {
                ordered = ordered.subList(0, remainingSeats);
            }
            for (Candidate candidate : ordered) {
                elected.add(candidate);
                continuing.remove(candidate);
                pendingSurplus.put(candidate, freshTallies.get(candidate));
            }
            if (elected.size() >= seats) {
                // Every seat is filled: the count ends here, and the rest of the excluded
                // candidate's papers stay where they are ("partially excluded", as the ACT's
                // official sheets put it).
                break;
            }
        }
        return parcelId;
    }

    /**
     * Picks which continuing candidate(s) to exclude this stage. Ordinarily that's just the
     * (tie-broken) single lowest candidate. When {@code batchExclusion} is enabled, ERS97 rule
     * 5.2.5 instead allows excluding several of the lowest candidates together in one stage:
     * the largest such set that leaves enough candidates for the seats, doesn't split
     * candidates on equal votes, and whose votes plus any pending surplus don't exceed the next
     * candidate's (see {@link #bulkExclusion}). Falls back to the single lowest candidate when
     * there's none.
     */
    private static List<Candidate> selectCandidatesToExclude(Set<Candidate> continuing,
            Map<Candidate, Fraction> tallies, Map<Candidate, Fraction> pendingSurplus, Fraction quota,
            int remainingSeats, boolean batchExclusion, TieBreakRule tieBreak, List<RoundRecord> priorRounds) {
        List<Candidate> ascending = new ArrayList<>(continuing);
        ascending.sort(Comparator.comparing(tallies::get));

        if (batchExclusion) {
            List<Candidate> batch = bulkExclusion(ascending, tallies, positiveSurplusTotal(pendingSurplus, quota),
                    remainingSeats);
            if (!batch.isEmpty()) {
                return batch;
            }
        }

        Fraction lowest = tallies.get(ascending.getFirst());
        List<Candidate> tiedLowest = continuing.stream()
                .filter(candidate -> tallies.get(candidate).compareTo(lowest) == 0)
                .toList();
        Candidate toEliminate = tiedLowest.size() == 1
                ? tiedLowest.getFirst()
                : tieBreak.candidateToEliminate(tiedLowest, priorRounds);
        return List.of(toEliminate);
    }

    /**
     * ERS97's bulk exclusion, as pyRCV2 counts it: the largest set of the lowest candidates
     * that leaves enough candidates for the seats, doesn't split candidates on equal votes, and
     * whose votes plus every pending surplus don't exceed the next candidate's. Empty if none.
     */
    private static List<Candidate> bulkExclusion(List<Candidate> ascending, Map<Candidate, Fraction> tallies,
            Fraction pendingSurpluses, int remainingSeats) {
        for (int size = ascending.size(); size >= 1; size--) {
            if (ascending.size() - size < remainingSeats) {
                continue;
            }
            if (size < ascending.size()) {
                Fraction next = tallies.get(ascending.get(size));
                if (tallies.get(ascending.get(size - 1)).equals(next)) {
                    continue;
                }
                Fraction total = pendingSurpluses;
                for (Candidate candidate : ascending.subList(0, size)) {
                    total = total.add(tallies.get(candidate));
                }
                if (total.compareTo(next) > 0) {
                    continue;
                }
            }
            return ascending.subList(0, size);
        }
        return List.of();
    }

    /**
     * Minneapolis's mathematical elimination, as its counts show it, lowest first: the largest set
     * of the lowest candidates whose votes together, with every surplus still to move, are less
     * than the next candidate's, leaving enough candidates for the seats (the 2006 ordinance,
     * 167.70(a)(1)d); and with them anyone ranked on too few ballots still in play to reach the
     * votes of the candidate holding the last of the seats left, which the 2017 and 2025 Park
     * Board counts did, defeating candidates the first spares.
     */
    private static List<Candidate> mathematicallyEliminated(List<BallotState> states, Set<Candidate> continuing,
            Map<Candidate, Fraction> tallies, Fraction quota, int remainingSeats) {
        if (continuing.size() <= remainingSeats) {
            return List.of();
        }
        List<Candidate> ascending = new ArrayList<>(continuing);
        ascending.sort(Comparator.comparing(tallies::get));
        Fraction total = Fraction.ZERO;
        for (Candidate candidate : ascending) {
            total = total.add(max(tallies.get(candidate).subtract(quota), Fraction.ZERO));
        }
        int lowest = 0;
        for (int size = 1; ascending.size() - size >= remainingSeats; size++) {
            total = total.add(tallies.get(ascending.get(size - 1)));
            if (total.compareTo(tallies.get(ascending.get(size))) < 0) {
                lowest = size;
            }
        }
        Fraction lastSeat = tallies.get(ascending.get(ascending.size() - remainingSeats));
        Map<Candidate, Fraction> reachable = reachableVotes(states, continuing);
        List<Candidate> defeated = new ArrayList<>();
        for (int i = 0; i < ascending.size(); i++) {
            Candidate candidate = ascending.get(i);
            if (i < lowest || reachable.getOrDefault(candidate, Fraction.ZERO).compareTo(lastSeat) < 0) {
                defeated.add(candidate);
            }
        }
        return defeated;
    }

    /**
     * The most votes each continuing candidate could still reach: the value of every ballot in
     * play that ranks them at or after the ranking it's at.
     */
    private static Map<Candidate, Fraction> reachableVotes(List<BallotState> states, Set<Candidate> continuing) {
        Map<Candidate, Fraction> reachable = new java.util.HashMap<>();
        for (BallotState state : states) {
            if (state.holders.isEmpty()) {
                continue;
            }
            Fraction value = state.value.multiply(Fraction.of(state.ballot.count()));
            Set<Candidate> ranked = new LinkedHashSet<>();
            List<Set<Candidate>> ranking = state.ballot.ranking();
            for (int i = state.nextIndex; i < ranking.size(); i++) {
                ranked.addAll(stillContinuing(ranking.get(i), continuing));
            }
            for (Candidate candidate : ranked) {
                reachable.merge(candidate, value, Fraction::add);
            }
        }
        return reachable;
    }

    /** The surpluses of candidates elected but not yet transferred: what's above quota. */
    private static Fraction positiveSurplusTotal(Map<Candidate, Fraction> pendingSurplus, Fraction quota) {
        Fraction total = Fraction.ZERO;
        for (Fraction frozenTally : pendingSurplus.values()) {
            if (frozenTally.compareTo(quota) > 0) {
                total = total.add(frozenTally.subtract(quota));
            }
        }
        return total;
    }

    /**
     * ERS97's surplus deferral, as pyRCV2 counts it: every pending surplus can wait if together
     * they're no more than the gap between the two lowest continuing candidates, and, with bulk
     * exclusion, no more than the gap pyRCV2 checks above the bulk batch (the candidate two
     * above it, as pyRCV2 indexes it).
     */
    private static boolean canDeferSurpluses(Set<Candidate> continuing, Map<Candidate, Fraction> tallies,
            Map<Candidate, Fraction> pendingSurplus, Fraction quota, int remainingSeats, boolean batchExclusion) {
        Fraction surpluses = positiveSurplusTotal(pendingSurplus, quota);
        List<Candidate> ascending = new ArrayList<>(continuing);
        ascending.sort(Comparator.comparing(tallies::get));
        if (ascending.size() < 2
                || surpluses.compareTo(tallies.get(ascending.get(1)).subtract(tallies.get(ascending.get(0)))) > 0) {
            return false;
        }
        if (batchExclusion) {
            List<Candidate> batch = bulkExclusion(ascending, tallies, surpluses, remainingSeats);
            if (!batch.isEmpty()) {
                if (batch.size() + 1 >= ascending.size()) {
                    return false;
                }
                Fraction excluded = Fraction.ZERO;
                for (Candidate candidate : batch) {
                    excluded = excluded.add(tallies.get(candidate));
                }
                if (surpluses.compareTo(tallies.get(ascending.get(batch.size() + 1)).subtract(excluded)) > 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * ERS97's votes required for election: the total active vote (continuing candidates' votes,
     * any still held by a candidate being excluded, and pending surpluses) divided by one more
     * than the seats still to fill, rounded up at the ruleset's precision. Null if the ruleset
     * doesn't use it.
     */
    private static Fraction votesRequired(StvRuleset ruleset, Fraction activeVotes, int remainingSeats) {
        if (!ruleset.votesRequired() || remainingSeats <= 0) {
            return null;
        }
        Fraction share = activeVotes.divide(Fraction.of(remainingSeats + 1));
        // Rounded up. (Truncating the negated share would round towards zero, so down.)
        return switch (ruleset.roundingRule()) {
            case Exact _ -> share;
            case TruncateDecimal truncate -> {
                Fraction down = truncate.apply(share);
                yield down.equals(share) ? share
                        : down.add(Fraction.of(BigInteger.ONE, BigInteger.TEN.pow(truncate.decimalPlaces())));
            }
        };
    }

    /**
     * Who is elected, in order, under ERS97's votes required, as pyRCV2 elects them: the
     * highest qualifying candidate first, then the next, and after each election the votes
     * required is worked out again (fewer seats remain, and the elected candidate's votes leave
     * the active vote but for their surplus), until nobody else reaches it or the quota.
     * {@code otherActive} is the active vote outside {@code continuing}: pending surpluses, and
     * anything an excluded candidate still holds.
     */
    private static List<Candidate> electOneByOne(Set<Candidate> continuing, Map<Candidate, Fraction> tallies,
            Fraction quota, Fraction otherActive, int remainingSeats, StvRuleset ruleset,
            List<RoundRecord> priorRounds) {
        List<Candidate> elected = new ArrayList<>();
        Set<Candidate> pool = new LinkedHashSet<>(continuing);
        Fraction extra = otherActive;
        while (true) {
            Fraction required =
                    votesRequired(ruleset, active(pool, tallies).add(extra), remainingSeats - elected.size());
            List<Candidate> qualifying = qualifying(pool, tallies, quota, required);
            if (qualifying.isEmpty() || elected.size() >= remainingSeats) {
                return elected;
            }
            while (!qualifying.isEmpty() && elected.size() < remainingSeats) {
                Candidate highest = pickHighest(qualifying, tallies, ruleset.tieBreak(), priorRounds);
                elected.add(highest);
                pool.remove(highest);
                extra = extra.add(max(tallies.get(highest).subtract(quota), Fraction.ZERO));
                // pyRCV2 picks the next from those meeting the threshold it started with ...
                qualifying = qualifying(pool, tallies, quota, required);
                // ... and works out the new one for its next pass.
                required = votesRequired(ruleset, active(pool, tallies).add(extra), remainingSeats - elected.size());
            }
        }
    }

    private static Fraction active(Set<Candidate> pool, Map<Candidate, Fraction> tallies) {
        Fraction total = Fraction.ZERO;
        for (Candidate candidate : pool) {
            total = total.add(tallies.get(candidate));
        }
        return total;
    }

    private static List<Candidate> qualifying(Set<Candidate> pool, Map<Candidate, Fraction> tallies, Fraction quota,
            Fraction required) {
        return pool.stream().filter(candidate -> elects(tallies.get(candidate), quota, required)).toList();
    }

    /** Whether a candidate on {@code tally} is elected: the quota, or ERS97's votes required. */
    private static boolean elects(Fraction tally, Fraction quota, Fraction votesRequired) {
        return tally.compareTo(quota) >= 0 || (votesRequired != null && tally.compareTo(votesRequired) >= 0);
    }

    private static Fraction max(Fraction a, Fraction b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static Candidate pickHighest(List<Candidate> candidates, Map<Candidate, Fraction> tallies,
            TieBreakRule tieBreak, List<RoundRecord> priorRounds) {
        Fraction highest = candidates.stream().map(tallies::get).max(Fraction::compareTo).orElseThrow();
        List<Candidate> tiedHighest = candidates.stream()
                .filter(candidate -> tallies.get(candidate).compareTo(highest) == 0)
                .toList();
        return tiedHighest.size() == 1
                ? tiedHighest.getFirst()
                : tieBreak.candidateToElectFirst(tiedHighest, priorRounds);
    }

    private static List<Candidate> sortedByTallyDescending(Set<Candidate> candidates,
            Map<Candidate, Fraction> tallies, TieBreakRule tieBreak, List<RoundRecord> priorRounds) {
        List<Candidate> remaining = new ArrayList<>(candidates);
        List<Candidate> result = new ArrayList<>(remaining.size());
        while (!remaining.isEmpty()) {
            Candidate top = pickHighest(remaining, tallies, tieBreak, priorRounds);
            result.add(top);
            remaining.remove(top);
        }
        return result;
    }

    /**
     * Every candidate's exact, unrounded share of the ballots they hold, and the exact value
     * of the exhausted ballots, kept up to date as the count goes: every change to a ballot's
     * holders or value is bracketed by {@link #remove} and {@link #add}, so a round's tallies
     * never need a pass over every ballot. {@link #tallies} rounds each continuing candidate's
     * exact total once -- a no-op for rulesets that already round per-parcel transfer values
     * ({@link info.fkhodkov.rankedchoice.stv.transfer.WigmTransfer}, {@link
     * info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer}, {@link
     * info.fkhodkov.rankedchoice.stv.transfer.LastParcelTransfer} -- a sum of already-rounded
     * multiples is already an exact multiple itself -- but essential for {@link
     * info.fkhodkov.rankedchoice.stv.transfer.LastParcelDeferredRoundingTransfer}, whose whole
     * point is deferring rounding to exactly this point instead. Every figure is an exact sum,
     * so keeping it running gives precisely what summing every ballot afresh would.
     *
     * <p>A ballot's holder(s) can remain an already-elected candidate forever under a
     * last-parcel-only transfer method (parcels it doesn't select are simply never moved
     * again), which is why only continuing candidates are read out. A tied ballot's value
     * splits evenly across everyone still sharing its front-most rank (see {@link
     * #assignNextHolder}) -- a singleton holder set is just the degenerate case, worth exactly
     * its own full value.
     */
    private static final class RunningTallies {
        private final Map<Candidate, Fraction> exact = new java.util.HashMap<>();
        private Fraction exhausted = Fraction.ZERO;
        // Rulesets rounding per transfer step (see endStep): the exact total at the last step's
        // end, and the sum of each step's rounded gain.
        private RoundingRule perStepRounding;
        private final Map<Candidate, Fraction> stepBase = new java.util.HashMap<>();
        private final Map<Candidate, Fraction> credited = new java.util.HashMap<>();
        private Fraction exhaustedBase = Fraction.ZERO;
        private Fraction exhaustedCredited = Fraction.ZERO;
        // Changes not yet applied: net ballot counts per (holders, value), so a transfer moving
        // many ballots at the same few values costs one exact operation per group, not per ballot.
        private final Map<Set<Candidate>, Map<Fraction, BigInteger>> pending = new LinkedHashMap<>();

        /** Built once, summing ballots per (holders, value) first so it's one pass. */
        static RunningTallies of(List<BallotState> states, RoundingRule perStepRounding) {
            RunningTallies running = new RunningTallies();
            running.perStepRounding = perStepRounding;
            Map<Set<Candidate>, Map<Fraction, BigInteger>> ballotsByHoldersAndValue = new LinkedHashMap<>();
            for (BallotState state : states) {
                ballotsByHoldersAndValue.computeIfAbsent(state.holders, _ -> new LinkedHashMap<>())
                        .merge(state.value, state.ballot.count(), BigInteger::add);
            }
            for (Map.Entry<Set<Candidate>, Map<Fraction, BigInteger>> group : ballotsByHoldersAndValue.entrySet()) {
                Set<Candidate> holders = group.getKey();
                for (Map.Entry<Fraction, BigInteger> byValue : group.getValue().entrySet()) {
                    Fraction total = Fraction.of(byValue.getValue()).multiply(byValue.getKey());
                    if (holders.isEmpty()) {
                        running.exhausted = running.exhausted.add(total);
                    } else {
                        Fraction share = total.divide(Fraction.of(holders.size()));
                        for (Candidate holder : holders) {
                            running.exact.merge(holder, share, Fraction::add);
                        }
                    }
                }
            }
            running.endStep();
            return running;
        }

        /**
         * Closes a transfer step (a surplus, or one value group of an exclusion) for a ruleset
         * that rounds per step: each candidate's gain over the step, rounded, is added to the
         * total they're credited with. The ACT's Schedule 4 rounds this way ("BP x TV ... any
         * fraction must be rounded down to 6 decimal places", per transfer), so its official
         * tallies, and its exhausted votes, are sums of rounded gains, not rounded sums. A
         * no-op otherwise.
         */
        void endStep() {
            if (perStepRounding == null) {
                return;
            }
            flush();
            for (Map.Entry<Candidate, Fraction> entry : exact.entrySet()) {
                Fraction base = stepBase.getOrDefault(entry.getKey(), Fraction.ZERO);
                Fraction gain = entry.getValue().subtract(base);
                if (entry.getValue().signum() == 0) {
                    // Holding no papers, they hold no votes, whatever rounding their losses had.
                    credited.put(entry.getKey(), Fraction.ZERO);
                    stepBase.put(entry.getKey(), Fraction.ZERO);
                } else if (gain.signum() != 0) {
                    credited.merge(entry.getKey(), perStepRounding.apply(gain), Fraction::add);
                    stepBase.put(entry.getKey(), entry.getValue());
                }
            }
            // Votes exhausted in the step are rounded the same way.
            exhaustedCredited = exhaustedCredited.add(perStepRounding.apply(exhausted.subtract(exhaustedBase)));
            exhaustedBase = exhausted;
        }

        void add(BallotState state) {
            apply(state, 1);
        }

        void remove(BallotState state) {
            apply(state, -1);
        }

        private void apply(BallotState state, int sign) {
            BigInteger count = sign > 0 ? state.ballot.count() : state.ballot.count().negate();
            pending.computeIfAbsent(state.holders, _ -> new LinkedHashMap<>())
                    .merge(state.value, count, BigInteger::add);
        }

        private void flush() {
            for (Map.Entry<Set<Candidate>, Map<Fraction, BigInteger>> group : pending.entrySet()) {
                Set<Candidate> holders = group.getKey();
                for (Map.Entry<Fraction, BigInteger> byValue : group.getValue().entrySet()) {
                    if (byValue.getValue().signum() == 0) {
                        continue;
                    }
                    Fraction total = Fraction.of(byValue.getValue()).multiply(byValue.getKey());
                    if (holders.isEmpty()) {
                        exhausted = exhausted.add(total);
                    } else {
                        Fraction share = total.divide(Fraction.of(holders.size()));
                        for (Candidate holder : holders) {
                            exact.merge(holder, share, Fraction::add);
                        }
                    }
                }
            }
            pending.clear();
        }

        /** Every continuing candidate's current tally, rounded, in {@code continuing}'s order. */
        Map<Candidate, Fraction> tallies(Set<Candidate> continuing, RoundingRule roundingRule) {
            flush();
            Map<Candidate, Fraction> tallies = new LinkedHashMap<>();
            for (Candidate candidate : continuing) {
                tallies.put(candidate, perStepRounding != null ? credited.getOrDefault(candidate, Fraction.ZERO)
                        : roundingRule.apply(exact.getOrDefault(candidate, Fraction.ZERO)));
            }
            return tallies;
        }

        Fraction exhausted() {
            flush();
            return perStepRounding != null ? exhaustedCredited : exhausted;
        }

        /** The votes held by candidates in neither {@code shown} nor {@code elected}. */
        Fraction heldOutside(Set<Candidate> shown, List<Candidate> elected, RoundingRule roundingRule) {
            flush();
            Fraction held = Fraction.ZERO;
            for (Map.Entry<Candidate, Fraction> entry : exact.entrySet()) {
                Candidate candidate = entry.getKey();
                if (!shown.contains(candidate) && !elected.contains(candidate)) {
                    held = held.add(perStepRounding != null ? credited.getOrDefault(candidate, Fraction.ZERO)
                            : roundingRule.apply(entry.getValue()));
                }
            }
            return held;
        }

        /** Surplus no ballot carries on: it counts as exhausted, though it's on no ballot. */
        void addNonTransferable(Fraction votes) {
            exhausted = exhausted.add(votes);
        }
    }

    private static int largestDenominatorBits(List<BallotState> states) {
        int largest = 0;
        for (BallotState state : states) {
            largest = Math.max(largest, state.value.denominator().bitLength());
        }
        return largest;
    }

    private record ParcelKey(int parcelId, Fraction value, boolean hasNextPreference) {
    }

    /** One group of an exclusion: see {@link ExclusionOrder}. */
    private record ExclusionGroup(boolean laterThanFirstPreferences, Fraction value, int parcel) {
    }

    /**
     * Moves {@code from}'s share of every ballot it (possibly jointly) holds onward, per the
     * ruleset's transfer method — see {@link TransferMethod}. For an untied ballot, {@code
     * from}'s "share" is just the ballot's whole value, same as ever. For a ballot currently
     * <em>tied</em> between {@code from} and one or more other still-continuing candidates
     * (see {@link #assignNextHolder}), only {@code from}'s {@code value / holders.size()}
     * portion is fed through the transfer method; whatever comes back replaces just that
     * portion, and the other tied candidates' shares are folded back into the ballot's pooled
     * value unchanged, exactly like {@code from} never touched them. The ballot's holder group
     * is then recomputed — if another tied candidate remains, the ballot's updated pool simply
     * re-splits between whoever's left at that same rank next round; only once every candidate
     * at that rank has gone does the ballot actually move on to its next preference.
     *
     * <p>A ballot whose parcel isn't included in the method's decision (e.g. an earlier parcel
     * under a last-parcel-only method) is left exactly as it is, credited to {@code from} for
     * good.
     */
    private static void transferSurplus(List<BallotState> states, RunningTallies running, Candidate from,
            Fraction surplus, Fraction heldVotes, TransferMethod transferMethod, RoundingRule roundingRule,
            Set<Candidate> continuing, ParcelIds parcelIds) {
        Map<ParcelKey, BigInteger> votesByKey = new LinkedHashMap<>();
        for (BallotState state : states) {
            if (state.holders.contains(from)) {
                Fraction share = shareOf(state, from);
                boolean hasNextPreference = hasNextContinuingPreference(state, continuing);
                votesByKey.merge(new ParcelKey(state.parcelId, share, hasNextPreference),
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

        Map<Integer, Fraction> newValues = transferMethod.computeTransferValues(surplus, heldVotes, parcels,
                roundingRule);

        // Rounded per parcel, each parcel moves as its own step, to its own new parcel.
        boolean perParcel = transferMethod.roundsGainsPerParcel();
        Map<ParcelKey, Integer> newIdBySource = new LinkedHashMap<>();
        int sharedId = perParcel ? -1 : parcelIds.next();
        for (BallotState state : bySourceParcel(states, from, perParcel)) {
            Fraction share = shareOf(state, from);
            boolean hasNextPreference = hasNextContinuingPreference(state, continuing);
            Integer id = idByKey.get(new ParcelKey(state.parcelId, share, hasNextPreference));
            Fraction newShare = newValues.get(id);
            if (newShare != null) {
                if (perParcel) {
                    ParcelKey source = new ParcelKey(state.parcelId, share, true);
                    if (!newIdBySource.containsKey(source) && !newIdBySource.isEmpty()) {
                        running.endStep();
                    }
                    newIdBySource.computeIfAbsent(source, _ -> parcelIds.next());
                }
                running.remove(state);
                state.value = state.value.subtract(share).add(newShare);
                state.parcelId = perParcel ? newIdBySource.get(new ParcelKey(state.parcelId, share, true)) : sharedId;
                assignNextHolder(state, continuing);
                running.add(state);
            }
        }

        // If every paper moved at its full value and that still fell short of the surplus (a
        // last-parcel transfer whose transferable papers are worth less than the surplus), the
        // rest can't be passed on. ERS97 counts it as non-transferable (pyRCV2: exhausted); the
        // ACT's official sheets count it as "loss by fraction", so for the ACT's method it's left
        // to the rounding loss.
        Fraction moved = Fraction.ZERO;
        Fraction movable = Fraction.ZERO;
        for (HeldParcel parcel : parcels) {
            Fraction newValue = newValues.get(parcel.id());
            if (newValue != null) {
                moved = moved.add(newValue.multiply(Fraction.of(parcel.voteCount())));
                movable = movable.add(parcel.totalValue());
            }
        }
        if (moved.signum() > 0 && moved.equals(movable) && moved.compareTo(surplus) < 0
                && !(transferMethod instanceof LastParcelDeferredRoundingTransfer)) {
            running.addNonTransferable(surplus.subtract(moved));
        }
        running.endStep();
    }

    /** {@code from}'s portion of a (possibly tied) ballot's current pooled value. */
    private static Fraction shareOf(BallotState state, Candidate from) {
        return state.value.divide(Fraction.of(state.holders.size()));
    }

    /**
     * Whether this ballot has a next continuing preference beyond its current holder(s),
     * without advancing it — last-parcel-family transfer methods (see {@link
     * info.fkhodkov.rankedchoice.stv.transfer.LastParcelTransfer}) need to know this per
     * ballot before deciding the transfer ratio, since ballots with no further preference are
     * excluded from its denominator. A ballot tied between more than one still-continuing
     * candidate always "has somewhere to go" regardless of anyone's downstream ranking — its
     * pool simply stays with whichever of the tied candidates remain.
     */
    private static boolean hasNextContinuingPreference(BallotState state, Set<Candidate> continuing) {
        if (state.holders.size() > 1) {
            return true;
        }
        List<Set<Candidate>> ranking = state.ballot.ranking();
        for (int i = state.nextIndex + 1; i < ranking.size(); i++) {
            if (!stillContinuing(ranking.get(i), continuing).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves every ballot currently (possibly jointly) held by the eliminated {@code from} on
     * to its next continuing preference, unchanged in value — elimination is never discounted,
     * regardless of transfer method. A ballot tied between {@code from} and another
     * still-continuing candidate is untouched in every other respect: its pool simply
     * re-splits among whoever's left sharing that rank (see {@link #assignNextHolder}).
     */
    private static void transferEliminatedBallots(List<BallotState> states, RunningTallies running, Candidate from,
            Set<Candidate> continuing, boolean perParcel, ParcelIds parcelIds) {
        Map<ParcelKey, Integer> newIdBySource = new LinkedHashMap<>();
        int sharedId = perParcel ? -1 : parcelIds.next();
        for (BallotState state : bySourceParcel(states, from, perParcel)) {
            int id = sharedId;
            if (perParcel) {
                // Rounded per parcel: each parcel moves as its own step, to its own new parcel.
                ParcelKey source = new ParcelKey(state.parcelId, shareOf(state, from), true);
                if (!newIdBySource.containsKey(source) && !newIdBySource.isEmpty()) {
                    running.endStep();
                }
                id = newIdBySource.computeIfAbsent(source, _ -> parcelIds.next());
            }
            running.remove(state);
            state.parcelId = id;
            assignNextHolder(state, continuing);
            running.add(state);
        }
        running.endStep();
    }

    /**
     * {@code from}'s ballots, grouped by the parcel they came in (the transfer and their share's
     * value) when {@code grouped}, in the order each parcel first appears; otherwise as they are.
     */
    private static List<BallotState> bySourceParcel(List<BallotState> states, Candidate from, boolean grouped) {
        List<BallotState> held = states.stream().filter(state -> state.holders.contains(from)).toList();
        if (!grouped) {
            return held;
        }
        Map<ParcelKey, List<BallotState>> byParcel = new LinkedHashMap<>();
        for (BallotState state : held) {
            byParcel.computeIfAbsent(new ParcelKey(state.parcelId, shareOf(state, from), true),
                    _ -> new ArrayList<>()).add(state);
        }
        return byParcel.values().stream().flatMap(List::stream).toList();
    }

    /** The next unused parcel id: each transfer's papers arrive as a new parcel. */
    private static final class ParcelIds {
        private int next = 1;

        int next() {
            return next++;
        }
    }

    /**
     * (Re-)computes a ballot's current holder group: the still-continuing candidates sharing
     * its front-most remaining rank. Unlike the old single-holder version, this deliberately
     * does <em>not</em> advance {@code nextIndex} past a rank as soon as a holder is found —
     * only once every candidate at that rank has left {@code continuing} does the next call
     * move on to the following rank. That's what lets a tied rank narrow one candidate at a
     * time (each departure re-examines the same rank) rather than jumping straight to the
     * ballot's next preference the instant any one member of the tie leaves — matching the
     * equal-preference algorithm in CRAN's {@code vote} package (Sevcikova, Silverman,
     * Raftery — see {@link StvCounter}'s class javadoc), verified by reading its source
     * directly: a ballot's pooled value re-splits among whichever tied candidates remain,
     * rather than each tied candidate's share being tracked as an independent sub-ballot from
     * the moment of the tie.
     */
    private static void assignNextHolder(BallotState state, Set<Candidate> continuing) {
        List<Set<Candidate>> ranking = state.ballot.ranking();
        while (state.nextIndex < ranking.size()) {
            Set<Candidate> group = stillContinuing(ranking.get(state.nextIndex), continuing);
            if (!group.isEmpty()) {
                state.holders = group;
                return;
            }
            state.nextIndex++;
        }
        state.holders = Set.of();
    }

    private static Set<Candidate> stillContinuing(Set<Candidate> rank, Set<Candidate> continuing) {
        if (rank.size() == 1) {
            return continuing.containsAll(rank) ? rank : Set.of();
        }
        Set<Candidate> group = new LinkedHashSet<>();
        for (Candidate candidate : rank) {
            if (continuing.contains(candidate)) {
                group.add(candidate);
            }
        }
        return group;
    }

    private static final class BallotState {
        private final Ballot ballot;
        private int nextIndex = 0;
        private Fraction value = Fraction.ONE;
        private int parcelId = 0;
        private Set<Candidate> holders = Set.of();

        BallotState(Ballot ballot) {
            this.ballot = ballot;
        }
    }
}
