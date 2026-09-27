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
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.HeldParcel;
import info.fkhodkov.rankedchoice.stv.transfer.LastParcelDeferredRoundingTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.LastParcelTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.TransferMethod;
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
 * LastParcelDeferredRoundingTransfer} — see {@link #defersSurplusTransfers}) defer every
 * surplus transfer rather than performing it the moment a candidate qualifies: a candidate
 * who crosses quota is immediately frozen (removed from {@code continuing}, added to {@code
 * elected}) but their surplus isn't transferred until a later round pops it as the largest
 * still-pending one — confirmed against the ERS97 rulebook's own model election, where a
 * frozen candidate's own surplus transfer can be deferred for many further rounds while other
 * business (including other candidates crossing quota later) takes priority. This also means
 * eliminating a candidate under these rulesets processes their held ballots one value-group at
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

    public static StvResult count(List<Candidate> candidates, List<Ballot> ballots, int seats, StvRuleset ruleset) {
        StvCounterValidation.validate(candidates, seats);
        ruleset.tiedRankings().requireSplitDefinedFor(ruleset.transferMethod());
        BallotFormality.FormalBallots formal = BallotFormality.apply(
                ballots, ruleset.tiedRankings(), ruleset.minimumPreferences(), candidates.size());
        List<Ballot> countedBallots = formal.ballots();

        Set<Candidate> continuing = new LinkedHashSet<>(candidates);
        List<BallotState> states = new ArrayList<>(countedBallots.size());
        BigInteger totalValidVotes = BigInteger.ZERO;
        for (Ballot ballot : countedBallots) {
            if (ballot.isBlank()) {
                continue;
            }
            totalValidVotes = totalValidVotes.add(ballot.count());
            BallotState state = new BallotState(ballot);
            assignNextHolder(state, continuing);
            states.add(state);
        }

        Fraction quota = ruleset.quota().compute(totalValidVotes, seats);
        boolean defers = defersSurplusTransfers(ruleset.transferMethod());

        List<Candidate> elected = new ArrayList<>();
        List<RoundRecord> rounds = new ArrayList<>();
        // Candidates who have crossed quota but whose surplus hasn't been transferred yet —
        // only ever populated when `defers` is true. Processed one at a time, largest first.
        Map<Candidate, Fraction> pendingSurplus = new LinkedHashMap<>();
        int roundNumber = 0;
        int nextParcelId = 1;

        while (elected.size() < seats) {
            roundNumber++;
            int remainingSeats = seats - elected.size();

            if (continuing.size() <= remainingSeats) {
                Map<Candidate, Fraction> tallies = computeTallies(states, continuing, ruleset.roundingRule());
                Fraction exhausted = exhaustedVotes(states);
                List<RoundRecord> priorRounds = List.copyOf(rounds);
                for (Candidate candidate : sortedByTallyDescending(continuing, tallies, ruleset.tieBreak(), priorRounds)) {
                    elected.add(candidate);
                    continuing.remove(candidate);
                    rounds.add(new RoundRecord(
                            roundNumber, tallies, new RoundAction.Elected(candidate, Fraction.ZERO), exhausted));
                    roundNumber++;
                }
                drainPendingSurplus(states, continuing, pendingSurplus, ruleset.roundingRule(), rounds, roundNumber);
                break;
            }

            Map<Candidate, Fraction> tallies = computeTallies(states, continuing, ruleset.roundingRule());
            Fraction exhausted = exhaustedVotes(states);
            List<RoundRecord> priorRounds = List.copyOf(rounds);
            List<Candidate> meetingQuota = continuing.stream()
                    .filter(candidate -> tallies.get(candidate).compareTo(quota) >= 0)
                    .toList();

            if (!meetingQuota.isEmpty()) {
                // Every candidate who meets quota in the same tally snapshot is declared
                // elected as a batch, before any of their surpluses are transferred — so none
                // of them can still receive (and thus inflate their own eventual surplus with)
                // a fellow batch member's transfer.
                List<Candidate> electedThisStage = sortedByTallyDescending(
                        new LinkedHashSet<>(meetingQuota), tallies, ruleset.tieBreak(), priorRounds);
                if (electedThisStage.size() > remainingSeats) {
                    electedThisStage = electedThisStage.subList(0, remainingSeats);
                }
                Map<Candidate, Fraction> frozenVotes = new LinkedHashMap<>();
                for (Candidate candidate : electedThisStage) {
                    frozenVotes.put(candidate, tallies.get(candidate));
                    elected.add(candidate);
                    continuing.remove(candidate);
                }

                if (!defers) {
                    // Surpluses are applied immediately, one at a time, largest first —
                    // confirmed correct for WIGM/fractional against real Scottish election data.
                    for (int i = 0; i < electedThisStage.size(); i++) {
                        Candidate candidate = electedThisStage.get(i);
                        Fraction candidateVotes = frozenVotes.get(candidate);
                        Fraction surplus = candidateVotes.subtract(quota);
                        Map<Candidate, Fraction> roundTallies = tallies;
                        Fraction roundExhausted = exhausted;
                        if (i > 0) {
                            roundTallies = computeTallies(states, continuing, ruleset.roundingRule());
                            roundTallies.put(candidate, candidateVotes);
                            roundExhausted = exhaustedVotes(states);
                        }
                        if (surplus.signum() > 0 && !continuing.isEmpty()) {
                            transferSurplus(states, candidate, surplus, ruleset.transferMethod(),
                                    ruleset.roundingRule(), continuing, nextParcelId++);
                        }
                        rounds.add(new RoundRecord(
                                roundNumber, roundTallies, new RoundAction.Elected(candidate, surplus), roundExhausted));
                        if (i < electedThisStage.size() - 1) {
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

            if (defers && !pendingSurplus.isEmpty()) {
                Candidate candidate = pickHighest(
                        new ArrayList<>(pendingSurplus.keySet()), pendingSurplus, ruleset.tieBreak(), priorRounds);
                Fraction frozenTally = pendingSurplus.remove(candidate);
                Fraction surplus = frozenTally.subtract(quota);
                // `tallies` and `exhausted` were captured before any of this round's actions
                // (including the freeze just above, if any) -- exactly the "before this
                // round's action" state the RoundRecord needs, so read them now, before the
                // transfer below mutates ballot state.
                Map<Candidate, Fraction> roundTallies = new LinkedHashMap<>(tallies);
                roundTallies.put(candidate, frozenTally);
                if (surplus.signum() > 0 && !continuing.isEmpty()) {
                    transferSurplus(states, candidate, surplus, ruleset.transferMethod(), ruleset.roundingRule(),
                            continuing, nextParcelId++);
                }
                rounds.add(new RoundRecord(
                        roundNumber, roundTallies, new RoundAction.Elected(candidate, surplus), exhausted));
                continue;
            }

            List<Candidate> toExclude = selectCandidatesToExclude(continuing, tallies, pendingSurplus, quota,
                    seats - elected.size(), defers && ruleset.batchExclusion(), ruleset.tieBreak(), priorRounds);
            for (Candidate candidate : toExclude) {
                continuing.remove(candidate);
            }
            if (defers) {
                nextParcelId = eliminateByValueGroup(states, toExclude, continuing, elected, pendingSurplus,
                        quota, seats, ruleset, priorRounds, nextParcelId);
            } else {
                transferEliminatedBallots(states, toExclude.getFirst(), continuing, nextParcelId++);
            }
            for (int i = 0; i < toExclude.size(); i++) {
                rounds.add(new RoundRecord(
                        roundNumber, tallies, new RoundAction.Eliminated(toExclude.get(i)), exhausted));
                if (i < toExclude.size() - 1) {
                    roundNumber++;
                }
            }
        }

        drainPendingSurplus(states, continuing, pendingSurplus, ruleset.roundingRule(), rounds, roundNumber);

        return new StvResult(elected, rounds, quota, formal.informal());
    }

    /**
     * Whether this ruleset's transfer method wants surplus transfers deferred (and
     * eliminations processed value-group by value-group) rather than performed immediately —
     * see the class-level javadoc.
     */
    private static boolean defersSurplusTransfers(TransferMethod transferMethod) {
        return switch (transferMethod) {
            case LastParcelTransfer _, LastParcelDeferredRoundingTransfer _ -> true;
            case FractionalTransfer _, WigmTransfer _ -> false;
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
    private static void drainPendingSurplus(List<BallotState> states, Set<Candidate> continuing,
            Map<Candidate, Fraction> pendingSurplus, RoundingRule roundingRule, List<RoundRecord> rounds,
            int roundNumber) {
        if (pendingSurplus.isEmpty()) {
            return;
        }
        Map<Candidate, Fraction> tallies = computeTallies(states, continuing, roundingRule);
        Fraction exhausted = exhaustedVotes(states);
        int number = roundNumber;
        for (Map.Entry<Candidate, Fraction> entry : pendingSurplus.entrySet()) {
            Map<Candidate, Fraction> roundTallies = new LinkedHashMap<>(tallies);
            roundTallies.put(entry.getKey(), entry.getValue());
            rounds.add(new RoundRecord(
                    number, roundTallies, new RoundAction.Elected(entry.getKey(), Fraction.ZERO), exhausted));
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
    private static int eliminateByValueGroup(List<BallotState> states, List<Candidate> from, Set<Candidate> continuing,
            List<Candidate> elected, Map<Candidate, Fraction> pendingSurplus, Fraction quota, int seats,
            StvRuleset ruleset, List<RoundRecord> priorRounds, int nextParcelId) {
        Set<Candidate> excluded = new LinkedHashSet<>(from);
        Map<Fraction, List<BallotState>> byValueDescending = new TreeMap<>(Comparator.reverseOrder());
        for (BallotState state : states) {
            if (!Collections.disjoint(state.holders, excluded)) {
                byValueDescending.computeIfAbsent(state.value, _ -> new ArrayList<>()).add(state);
            }
        }

        int parcelId = nextParcelId;
        for (List<BallotState> group : byValueDescending.values()) {
            int thisGroupParcelId = parcelId++;
            for (BallotState state : group) {
                state.parcelId = thisGroupParcelId;
                assignNextHolder(state, continuing);
            }

            int remainingSeats = seats - elected.size();
            if (remainingSeats <= 0) {
                continue;
            }
            Map<Candidate, Fraction> freshTallies = computeTallies(states, continuing, ruleset.roundingRule());
            List<Candidate> newlyQualified = continuing.stream()
                    .filter(candidate -> freshTallies.get(candidate).compareTo(quota) >= 0)
                    .toList();
            if (newlyQualified.isEmpty()) {
                continue;
            }
            List<Candidate> ordered = sortedByTallyDescending(
                    new LinkedHashSet<>(newlyQualified), freshTallies, ruleset.tieBreak(), priorRounds);
            if (ordered.size() > remainingSeats) {
                ordered = ordered.subList(0, remainingSeats);
            }
            for (Candidate candidate : ordered) {
                elected.add(candidate);
                continuing.remove(candidate);
                pendingSurplus.put(candidate, freshTallies.get(candidate));
            }
        }
        return parcelId;
    }

    /**
     * Picks which continuing candidate(s) to exclude this stage. Ordinarily that's just the
     * (tie-broken) single lowest candidate. When {@code batchExclusion} is enabled, ERS97 rule
     * 5.2.5 instead allows excluding several of the lowest candidates together in one stage:
     * grow the batch upward from the bottom while enough candidates remain to fill the
     * remaining seats and the batch's combined tally, plus the total of any deferred surplus,
     * still doesn't exceed the tally of the next candidate above it — confirmed against a real,
     * independent implementation (OpenSTV) reproducing the exact batch ERS97's own rulebook
     * model election selects. Falls back to the single lowest candidate whenever that growth
     * can't get started (the lowest candidate alone already exceeds the one above them).
     */
    private static List<Candidate> selectCandidatesToExclude(Set<Candidate> continuing,
            Map<Candidate, Fraction> tallies, Map<Candidate, Fraction> pendingSurplus, Fraction quota,
            int remainingSeats, boolean batchExclusion, TieBreakRule tieBreak, List<RoundRecord> priorRounds) {
        List<Candidate> ascending = new ArrayList<>(continuing);
        ascending.sort(Comparator.comparing(tallies::get));

        if (batchExclusion) {
            Fraction deferredSurplusTotal = Fraction.ZERO;
            for (Fraction frozenTally : pendingSurplus.values()) {
                deferredSurplusTotal = deferredSurplusTotal.add(frozenTally.subtract(quota));
            }
            List<Candidate> batch = new ArrayList<>();
            Fraction batchTotal = Fraction.ZERO;
            for (int i = 0; i < ascending.size(); i++) {
                if (continuing.size() - (i + 1) < remainingSeats) {
                    break;
                }
                Candidate candidate = ascending.get(i);
                Fraction tentativeTotal = batchTotal.add(tallies.get(candidate));
                if (i + 1 < ascending.size()) {
                    Fraction nextAboveTally = tallies.get(ascending.get(i + 1));
                    if (tentativeTotal.add(deferredSurplusTotal).compareTo(nextAboveTally) > 0) {
                        break;
                    }
                }
                batch.add(candidate);
                batchTotal = tentativeTotal;
            }
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

    private static Candidate pickHighest(List<Candidate> candidates, Map<Candidate, Fraction> tallies,
            TieBreakRule tieBreak, List<RoundRecord> priorRounds) {
        Fraction highest = candidates.stream().map(tallies::get).max(Fraction::compareTo).orElseThrow();
        List<Candidate> tiedHighest = candidates.stream()
                .filter(candidate -> tallies.get(candidate).compareTo(highest) == 0)
                .toList();
        return tiedHighest.size() == 1 ? tiedHighest.getFirst() : tieBreak.candidateToElectFirst(tiedHighest, priorRounds);
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
     * Every continuing candidate's current tally. Each candidate's summed total is passed
     * through {@code roundingRule} once — a no-op for rulesets that already round per-parcel
     * transfer values ({@link info.fkhodkov.rankedchoice.stv.transfer.WigmTransfer}, {@link
     * info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer}, {@link
     * info.fkhodkov.rankedchoice.stv.transfer.LastParcelTransfer} — a sum of already-rounded
     * multiples is already an exact multiple itself — but essential for {@link
     * info.fkhodkov.rankedchoice.stv.transfer.LastParcelDeferredRoundingTransfer}, whose whole
     * point is deferring rounding to exactly this point instead.
     */
    private static Map<Candidate, Fraction> computeTallies(
            List<BallotState> states, Set<Candidate> continuing, RoundingRule roundingRule) {
        Map<Candidate, Fraction> tallies = new LinkedHashMap<>();
        for (Candidate candidate : continuing) {
            tallies.put(candidate, Fraction.ZERO);
        }
        for (BallotState state : states) {
            // A ballot's holder(s) can remain an already-elected candidate forever under a
            // last-parcel-only transfer method (parcels it doesn't select are simply never
            // moved again), so only count holders still in the running. A tied ballot's value
            // splits evenly across everyone still sharing its front-most rank (see {@link
            // #assignNextHolder}) -- a singleton holder set is just the degenerate case, worth
            // exactly its own full value.
            if (state.holders.isEmpty()) {
                continue;
            }
            Fraction share = Fraction.of(state.ballot.count()).multiply(state.value)
                    .divide(Fraction.of(state.holders.size()));
            for (Candidate holder : state.holders) {
                if (continuing.contains(holder)) {
                    tallies.merge(holder, share, Fraction::add);
                }
            }
        }
        tallies.replaceAll((_, tally) -> roundingRule.apply(tally));
        return tallies;
    }

    private static Fraction exhaustedVotes(List<BallotState> states) {
        Fraction total = Fraction.ZERO;
        for (BallotState state : states) {
            if (state.holders.isEmpty()) {
                total = total.add(Fraction.of(state.ballot.count()).multiply(state.value));
            }
        }
        return total;
    }

    private record ParcelKey(int parcelId, Fraction value, boolean hasNextPreference) {
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
    private static void transferSurplus(List<BallotState> states, Candidate from, Fraction surplus,
            TransferMethod transferMethod, RoundingRule roundingRule, Set<Candidate> continuing, int newParcelId) {
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

        Map<Integer, Fraction> newValues = transferMethod.computeTransferValues(surplus, parcels, roundingRule);

        for (BallotState state : states) {
            if (state.holders.contains(from)) {
                Fraction share = shareOf(state, from);
                boolean hasNextPreference = hasNextContinuingPreference(state, continuing);
                Integer id = idByKey.get(new ParcelKey(state.parcelId, share, hasNextPreference));
                Fraction newShare = newValues.get(id);
                if (newShare != null) {
                    state.value = state.value.subtract(share).add(newShare);
                    state.parcelId = newParcelId;
                    assignNextHolder(state, continuing);
                }
            }
        }
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
    private static void transferEliminatedBallots(List<BallotState> states, Candidate from,
            Set<Candidate> continuing, int newParcelId) {
        for (BallotState state : states) {
            if (state.holders.contains(from)) {
                state.parcelId = newParcelId;
                assignNextHolder(state, continuing);
            }
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
