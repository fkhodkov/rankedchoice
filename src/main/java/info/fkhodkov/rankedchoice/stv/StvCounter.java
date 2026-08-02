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
import info.fkhodkov.rankedchoice.stv.tiebreak.TieBreakRule;

/**
 * Runs a single transferable vote count. Each round either elects the candidate(s) meeting
 * quota (transferring their surplus onward) or eliminates the lowest continuing candidate
 * (transferring all their votes onward), until every seat is filled — including the standard
 * shortcut where, once exactly as many candidates remain continuing as there are seats left,
 * they are all declared elected without further transfers.
 *
 * <p>Candidates must already exclude any withdrawn from standing; {@code ballots} must not
 * contain tied rankings (every rank a singleton) — tie support is a later addition.
 */
public final class StvCounter {

    private StvCounter() {
    }

    public static StvResult count(List<Candidate> candidates, List<Ballot> ballots, int seats, StvRuleset ruleset) {
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive, got: " + seats);
        }
        if (seats > candidates.size()) {
            throw new IllegalArgumentException(
                    "cannot fill " + seats + " seat(s) with only " + candidates.size() + " candidate(s)");
        }
        for (Ballot ballot : ballots) {
            for (Set<Candidate> rank : ballot.ranking()) {
                if (rank.size() > 1) {
                    throw new UnsupportedOperationException("StvCounter does not yet support tied rankings");
                }
            }
        }

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

        while (elected.size() < seats) {
            roundNumber++;
            int remainingSeats = seats - elected.size();

            if (continuing.size() <= remainingSeats) {
                Map<Candidate, Fraction> tallies = computeTallies(states, continuing);
                Fraction exhausted = exhaustedVotes(states);
                for (Candidate candidate : sortedByTallyDescending(continuing, tallies, ruleset.tieBreak())) {
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
            List<Candidate> meetingQuota = continuing.stream()
                    .filter(candidate -> tallies.get(candidate).compareTo(quota) >= 0)
                    .toList();

            if (!meetingQuota.isEmpty()) {
                Candidate toElect = pickHighest(meetingQuota, tallies, ruleset.tieBreak());
                Fraction candidateVotes = tallies.get(toElect);
                Fraction surplus = candidateVotes.subtract(quota);
                elected.add(toElect);
                continuing.remove(toElect);
                if (surplus.signum() > 0 && !continuing.isEmpty()) {
                    Fraction transferValue = ruleset.transferMethod().surplusTransferValue(surplus, candidateVotes);
                    transferBallots(states, toElect, transferValue, continuing);
                }
                rounds.add(new RoundRecord(
                        roundNumber, tallies, new RoundAction.Elected(toElect, surplus), exhausted));
            } else {
                Fraction lowest = continuing.stream().map(tallies::get).min(Fraction::compareTo).orElseThrow();
                List<Candidate> tiedLowest = continuing.stream()
                        .filter(candidate -> tallies.get(candidate).compareTo(lowest) == 0)
                        .toList();
                Candidate toEliminate = tiedLowest.size() == 1
                        ? tiedLowest.getFirst()
                        : ruleset.tieBreak().resolveTie(tiedLowest);
                continuing.remove(toEliminate);
                transferBallots(states, toEliminate, Fraction.ONE, continuing);
                rounds.add(new RoundRecord(
                        roundNumber, tallies, new RoundAction.Eliminated(toEliminate), exhausted));
            }
        }

        return new StvResult(elected, rounds, quota);
    }

    private static Candidate pickHighest(List<Candidate> candidates, Map<Candidate, Fraction> tallies,
            TieBreakRule tieBreak) {
        Fraction highest = candidates.stream().map(tallies::get).max(Fraction::compareTo).orElseThrow();
        List<Candidate> tiedHighest = candidates.stream()
                .filter(candidate -> tallies.get(candidate).compareTo(highest) == 0)
                .toList();
        return tiedHighest.size() == 1 ? tiedHighest.getFirst() : tieBreak.resolveTie(tiedHighest);
    }

    private static List<Candidate> sortedByTallyDescending(Set<Candidate> candidates,
            Map<Candidate, Fraction> tallies, TieBreakRule tieBreak) {
        List<Candidate> remaining = new ArrayList<>(candidates);
        List<Candidate> result = new ArrayList<>(remaining.size());
        while (!remaining.isEmpty()) {
            Candidate top = pickHighest(remaining, tallies, tieBreak);
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
            if (state.holder != null) {
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

    /** Moves every ballot currently held by {@code from} to its next continuing preference. */
    private static void transferBallots(List<BallotState> states, Candidate from, Fraction transferValue,
            Set<Candidate> continuing) {
        for (BallotState state : states) {
            if (from.equals(state.holder)) {
                state.value = state.value.multiply(transferValue);
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
        private Candidate holder;

        BallotState(Ballot ballot) {
            this.ballot = ballot;
        }
    }
}
