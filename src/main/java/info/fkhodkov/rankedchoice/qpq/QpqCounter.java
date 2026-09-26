package info.fkhodkov.rankedchoice.qpq;

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

    public static QpqResult count(List<Candidate> candidates, List<Ballot> ballots, int seats, QpqRuleset ruleset) {
        validate(candidates, ballots, seats);

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
            stageNumber++;
            boolean restartedThisStage = restartPending;
            if (restartPending) {
                hopeful.addAll(elected);
                elected.clear();
                for (BallotState state : states) {
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
            BigInteger va = BigInteger.ZERO;
            Fraction tx = Fraction.ZERO;
            for (BallotState state : states) {
                Fraction weightedCredit = Fraction.of(state.ballot.count()).multiply(state.electedCredit);
                if (state.holder == null) {
                    tx = tx.add(weightedCredit);
                } else {
                    va = va.add(state.ballot.count());
                    vc.merge(state.holder, state.ballot.count(), BigInteger::add);
                    tc.merge(state.holder, weightedCredit, Fraction::add);
                }
            }

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

        return new QpqResult(List.copyOf(elected), rounds);
    }

    private static void validate(List<Candidate> candidates, List<Ballot> ballots, int seats) {
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
                    throw new UnsupportedOperationException("QpqCounter does not yet support tied rankings");
                }
            }
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
