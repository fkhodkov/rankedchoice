package info.fkhodkov.rankedchoice.stv;

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
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;
import info.fkhodkov.rankedchoice.stv.transfer.HeldParcel;
import info.fkhodkov.rankedchoice.stv.transfer.TransferMethod;

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
 * <p>Candidates must already exclude any withdrawn from standing; {@code ballots} must not
 * contain tied rankings (every rank a singleton) — tie support is a later addition.
 */
public final class StvCounter {

    private StvCounter() {
    }

    public static StvResult count(List<Candidate> candidates, List<Ballot> ballots, int seats, StvRuleset ruleset) {
        StvCounterValidation.validate(candidates, ballots, seats);

        Set<Candidate> continuing = new LinkedHashSet<>(candidates);
        List<BallotState> states = new ArrayList<>(ballots.size());
        BigInteger totalValidVotes = BigInteger.ZERO;
        for (Ballot ballot : ballots) {
            if (ballot.isBlank()) {
                continue;
            }
            totalValidVotes = totalValidVotes.add(ballot.count());
            BallotState state = new BallotState(ballot);
            assignNextHolder(state, continuing);
            states.add(state);
        }

        Fraction quota = ruleset.quota().compute(totalValidVotes, seats);

        List<Candidate> elected = new ArrayList<>();
        List<RoundRecord> rounds = new ArrayList<>();
        int roundNumber = 0;
        int nextParcelId = 1;

        while (elected.size() < seats) {
            roundNumber++;
            int remainingSeats = seats - elected.size();

            if (continuing.size() <= remainingSeats) {
                Map<Candidate, Fraction> tallies = computeTallies(states, continuing);
                Fraction exhausted = exhaustedVotes(states);
                List<RoundRecord> priorRounds = List.copyOf(rounds);
                for (Candidate candidate : sortedByTallyDescending(continuing, tallies, ruleset.tieBreak(), priorRounds)) {
                    elected.add(candidate);
                    continuing.remove(candidate);
                    rounds.add(new RoundRecord(
                            roundNumber, tallies, new RoundAction.Elected(candidate, Fraction.ZERO), exhausted));
                    roundNumber++;
                }
                break;
            }

            Map<Candidate, Fraction> tallies = computeTallies(states, continuing);
            Fraction exhausted = exhaustedVotes(states);
            List<RoundRecord> priorRounds = List.copyOf(rounds);
            List<Candidate> meetingQuota = continuing.stream()
                    .filter(candidate -> tallies.get(candidate).compareTo(quota) >= 0)
                    .toList();

            if (!meetingQuota.isEmpty()) {
                // Every candidate who meets quota in the same tally snapshot is declared
                // elected as a batch, before any of their surpluses are transferred — so none
                // of them can still receive (and thus inflate their own eventual surplus with)
                // a fellow batch member's transfer. Surpluses are then applied one at a time,
                // largest first, same as the single-candidate case below.
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
                for (int i = 0; i < electedThisStage.size(); i++) {
                    Candidate candidate = electedThisStage.get(i);
                    Fraction candidateVotes = frozenVotes.get(candidate);
                    Fraction surplus = candidateVotes.subtract(quota);
                    Map<Candidate, Fraction> roundTallies = tallies;
                    Fraction roundExhausted = exhausted;
                    if (i > 0) {
                        roundTallies = computeTallies(states, continuing);
                        roundTallies.put(candidate, candidateVotes);
                        roundExhausted = exhaustedVotes(states);
                    }
                    if (surplus.signum() > 0 && !continuing.isEmpty()) {
                        transferSurplus(states, candidate, surplus, ruleset.transferMethod(), ruleset.roundingRule(),
                                continuing, nextParcelId++);
                    }
                    rounds.add(new RoundRecord(
                            roundNumber, roundTallies, new RoundAction.Elected(candidate, surplus), roundExhausted));
                    if (i < electedThisStage.size() - 1) {
                        roundNumber++;
                    }
                }
            } else {
                Fraction lowest = continuing.stream().map(tallies::get).min(Fraction::compareTo).orElseThrow();
                List<Candidate> tiedLowest = continuing.stream()
                        .filter(candidate -> tallies.get(candidate).compareTo(lowest) == 0)
                        .toList();
                Candidate toEliminate = tiedLowest.size() == 1
                        ? tiedLowest.getFirst()
                        : ruleset.tieBreak().candidateToEliminate(tiedLowest, priorRounds);
                continuing.remove(toEliminate);
                transferEliminatedBallots(states, toEliminate, continuing, nextParcelId++);
                rounds.add(new RoundRecord(
                        roundNumber, tallies, new RoundAction.Eliminated(toEliminate), exhausted));
            }
        }

        return new StvResult(elected, rounds, quota);
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

    private static Map<Candidate, Fraction> computeTallies(List<BallotState> states, Set<Candidate> continuing) {
        Map<Candidate, Fraction> tallies = new LinkedHashMap<>();
        for (Candidate candidate : continuing) {
            tallies.put(candidate, Fraction.ZERO);
        }
        for (BallotState state : states) {
            // A ballot's holder can remain an already-elected candidate forever under a
            // last-parcel-only transfer method (parcels it doesn't select are simply never
            // moved again), so only count holders still in the running.
            if (state.holder != null && continuing.contains(state.holder)) {
                tallies.merge(state.holder, Fraction.of(state.ballot.count()).multiply(state.value), Fraction::add);
            }
        }
        return tallies;
    }

    private static Fraction exhaustedVotes(List<BallotState> states) {
        Fraction total = Fraction.ZERO;
        for (BallotState state : states) {
            if (state.holder == null) {
                total = total.add(Fraction.of(state.ballot.count()).multiply(state.value));
            }
        }
        return total;
    }

    private record ParcelKey(int parcelId, Fraction value) {
    }

    /**
     * Moves the newly-elected {@code from}'s ballots to their next continuing preference,
     * per the ruleset's transfer method — see {@link TransferMethod}. A ballot whose parcel
     * isn't included in the method's decision (e.g. an earlier parcel under a last-parcel-only
     * method) is left exactly as it is, credited to {@code from} for good.
     */
    private static void transferSurplus(List<BallotState> states, Candidate from, Fraction surplus,
            TransferMethod transferMethod, RoundingRule roundingRule, Set<Candidate> continuing, int newParcelId) {
        Map<ParcelKey, BigInteger> votesByKey = new LinkedHashMap<>();
        for (BallotState state : states) {
            if (from.equals(state.holder)) {
                votesByKey.merge(new ParcelKey(state.parcelId, state.value), state.ballot.count(), BigInteger::add);
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
            parcels.add(new HeldParcel(id, entry.getKey().parcelId(), entry.getKey().value(), entry.getValue()));
        }

        Map<Integer, Fraction> newValues = transferMethod.computeTransferValues(surplus, parcels, roundingRule);

        for (BallotState state : states) {
            if (from.equals(state.holder)) {
                Integer id = idByKey.get(new ParcelKey(state.parcelId, state.value));
                Fraction newValue = newValues.get(id);
                if (newValue != null) {
                    state.value = newValue;
                    state.parcelId = newParcelId;
                    assignNextHolder(state, continuing);
                }
            }
        }
    }

    /**
     * Moves every ballot currently held by the eliminated {@code from} to its next continuing
     * preference, unchanged in value — elimination is never discounted, regardless of
     * transfer method.
     */
    private static void transferEliminatedBallots(List<BallotState> states, Candidate from,
            Set<Candidate> continuing, int newParcelId) {
        for (BallotState state : states) {
            if (from.equals(state.holder)) {
                state.parcelId = newParcelId;
                assignNextHolder(state, continuing);
            }
        }
    }

    /** Advances a ballot to the next continuing candidate in its ranking, or exhausts it. */
    private static void assignNextHolder(BallotState state, Set<Candidate> continuing) {
        List<Set<Candidate>> ranking = state.ballot.ranking();
        while (state.nextIndex < ranking.size()) {
            Candidate candidate = ranking.get(state.nextIndex).iterator().next();
            state.nextIndex++;
            if (continuing.contains(candidate)) {
                state.holder = candidate;
                return;
            }
        }
        state.holder = null;
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
}
