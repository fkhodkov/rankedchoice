package info.fkhodkov.rankedchoice.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.borda.BordaMethod;
import info.fkhodkov.rankedchoice.borda.ClassicBorda;
import info.fkhodkov.rankedchoice.cambridge.CambridgeCounter;
import info.fkhodkov.rankedchoice.cambridge.CambridgeRuleset;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.cpostv.CpoStvCounter;
import info.fkhodkov.rankedchoice.cpostv.CpoStvRuleset;
import info.fkhodkov.rankedchoice.qpq.QpqCounter;
import info.fkhodkov.rankedchoice.qpq.QpqRuleset;
import info.fkhodkov.rankedchoice.schulze.PairwiseMatrix;
import info.fkhodkov.rankedchoice.schulze.SchulzeMethod;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeProportionalRanking;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeStvCounter;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.StvRuleset;
import info.fkhodkov.rankedchoice.stv.meek.MeekCounter;
import info.fkhodkov.rankedchoice.stv.meek.MeekRuleset;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;

/**
 * Every counter a caller can reach without {@link Tabulator} refuses a candidate listed twice,
 * which Borda once counted twice (of 2 ballots for A and 3 for B, it gave A 20 points to B's 10).
 * 50 ballots here, since Cambridge STV needs that many a seat.
 */
class CountersRefuseACandidateTwiceTest {

    private static final Candidate A = Candidate.of("A");
    private static final Candidate B = Candidate.of("B");
    private static final List<Ballot> BALLOTS = List.of(
            new Ballot(List.of(Set.of(A)), BigInteger.valueOf(20)),
            new Ballot(List.of(Set.of(B)), BigInteger.valueOf(30)));

    /** Each counter, by name, counting these candidates for one seat. */
    private static Map<String, Consumer<List<Candidate>>> counters() {
        DeclaredOrderTieBreak tieBreak = new DeclaredOrderTieBreak(List.of(A, B));
        Map<String, Consumer<List<Candidate>>> counters = new LinkedHashMap<>();
        counters.put("stv", candidates -> StvCounter.count(candidates, BALLOTS, 1,
                new StvRuleset(DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE, tieBreak)));
        counters.put("meek", candidates -> MeekCounter.count(candidates, BALLOTS, 1,
                new MeekRuleset(Exact.INSTANCE, tieBreak)));
        counters.put("cambridge", candidates -> CambridgeCounter.count(candidates, BALLOTS, 1,
                new CambridgeRuleset(tieBreak)));
        counters.put("qpq", candidates -> QpqCounter.count(candidates, BALLOTS, 1, new QpqRuleset(tieBreak)));
        counters.put("cpo-stv", candidates -> CpoStvCounter.count(candidates, BALLOTS, 1,
                new CpoStvRuleset(DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE)));
        counters.put("schulze", candidates -> SchulzeMethod.count(candidates, BALLOTS));
        counters.put("pairwise matrix", candidates -> PairwiseMatrix.compute(candidates, BALLOTS));
        counters.put("schulze-stv", candidates -> SchulzeStvCounter.count(candidates, BALLOTS, 1));
        counters.put("schulze-proportional", candidates -> SchulzeProportionalRanking.count(candidates, BALLOTS));
        counters.put("borda", candidates -> BordaMethod.count(candidates, BALLOTS, ClassicBorda.INSTANCE));
        return counters;
    }

    @Test
    void everyCounterRefusesACandidateListedTwice() {
        counters().forEach((name, counter) -> assertThatThrownBy(() -> counter.accept(List.of(A, A, B)))
                .as(name)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidate A (\"A\") is listed twice"));
    }

    @Test
    void twoCandidatesOfTheSameNameAreCountedAndLeftToTheReports() {
        // Distinct candidates, so counted correctly; the reports, which name them, refuse them.
        Candidate otherA = new Candidate("A2", "A");
        counters().forEach((name, counter) -> counter.accept(List.of(A, otherA, B)));
    }

    @Test
    void everyCounterStillCountsTheCandidatesOnce() {
        counters().forEach((name, counter) -> counter.accept(List.of(A, B)));
        assertThat(BordaMethod.count(List.of(A, B), BALLOTS, ClassicBorda.INSTANCE).rankingBestToWorst())
                .containsExactly(B, A);
    }
}
