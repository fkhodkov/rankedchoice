package info.fkhodkov.rankedchoice.cpostv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.stv.TiedRankingPolicy;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.quota.HagenbachBischoffQuota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.WigmTransfer;

/**
 * The CPO-STV Wikipedia article's own worked example: 5 candidates, 3 seats, 100 voters,
 * Hagenbach-Bischoff quota (100/4 = 25 exactly). Hand-verified independently against the
 * article's own numbers (see {@link #comparingTwoOutcomesDirectlyMatchesTheWikipediaWorkedExample}
 * for the full derivation) before writing this test.
 */
class CpoStvCounterTest {

    private static final Candidate ANDREA = Candidate.of("Andrea");
    private static final Candidate BRAD = Candidate.of("Brad");
    private static final Candidate CARTER = Candidate.of("Carter");
    private static final Candidate DELILAH = Candidate.of("Delilah");
    private static final Candidate ERIC = Candidate.of("Eric");

    private static final List<Candidate> CANDIDATES = List.of(ANDREA, BRAD, CARTER, DELILAH, ERIC);

    private static final List<Ballot> BALLOTS = List.of(
            ballot(25, ANDREA),
            ballot(34, CARTER, BRAD, DELILAH),
            ballot(7, BRAD, DELILAH),
            ballot(8, DELILAH, BRAD),
            ballot(5, DELILAH, ERIC),
            ballot(21, ERIC, DELILAH));

    private static final CpoStvRuleset RULESET =
            new CpoStvRuleset(HagenbachBischoffQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE);

    @Test
    void comparingTwoOutcomesDirectlyMatchesTheWikipediaWorkedExample() {
        // Outcome A = {Andrea, Carter, Delilah} vs Outcome B = {Andrea, Carter, Eric}. Quota is
        // 100/(3+1) = 25 (Hagenbach-Bischoff, no rounding needed here). Standing = A u B = all
        // 5 candidates minus nobody -- wait, Brad is in neither A nor B, so Brad is excluded:
        // his 7 (Brad,Delilah) ballots transfer fully to Delilah. Raw first preferences were
        // Andrea 25, Brad 7, Carter 34, Delilah 13 (8+5), Eric 21; after excluding Brad,
        // Delilah rises to 13+7=20. Carter (shared by both outcomes) has surplus 34-25=9; all
        // of Carter's 34 ballots are Carter>Brad>Delilah, and Brad is already excluded, so all
        // 9 surplus votes go to Delilah, who reaches 20+9=29. Andrea (also shared) is exactly
        // at quota, no surplus. Final: Andrea=25, Carter=25, Delilah=29, Eric=21 (untouched,
        // unique to B). Score(A) = 25+25+29 = 79. Score(B) = 25+25+21 = 71 -- A wins, matching
        // the article's own arithmetic exactly.
        Set<Candidate> outcomeA = Set.of(ANDREA, CARTER, DELILAH);
        Set<Candidate> outcomeB = Set.of(ANDREA, CARTER, ERIC);

        CpoStvResult result = CpoStvCounter.count(CANDIDATES, BALLOTS, 3, RULESET);

        assertThat(result.outcomeScores().get(outcomeA).get(outcomeB)).isEqualTo(Fraction.of(79));
        assertThat(result.outcomeScores().get(outcomeB).get(outcomeA)).isEqualTo(Fraction.of(71));
    }

    @Test
    void theOverallWinningOutcomeIsAndreaCarterDelilah() {
        CpoStvResult result = CpoStvCounter.count(CANDIDATES, BALLOTS, 3, RULESET);

        assertThat(result.winners()).containsExactly(Set.of(ANDREA, CARTER, DELILAH));
        assertThat(result.ranking().getFirst()).hasSize(1);
    }

    @Test
    void aSingleFeasibleOutcomeNeedsNoComparisonAtAll() {
        // Exactly as many candidates as seats -- only one possible outcome.
        CpoStvResult result = CpoStvCounter.count(List.of(ANDREA, BRAD), BALLOTS, 2, RULESET);

        assertThat(result.winners()).containsExactly(Set.of(ANDREA, BRAD));
        assertThat(result.outcomeScores()).isEmpty();
    }

    @Test
    void rejectsACandidateSeatCombinationBeyondTheOutcomeCap() {
        List<Candidate> manyCandidates = IntStream.range(0, 30)
                .mapToObj(i -> Candidate.of("C" + i))
                .toList();
        // C(30, 15) is far beyond MAX_OUTCOMES.
        assertThat(CpoStvCounter.MAX_OUTCOMES).isLessThan(155_117_520);

        assertThatThrownBy(() -> CpoStvCounter.count(manyCandidates, BALLOTS, 15, RULESET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds this implementation's cap");
    }

    @Test
    void wigmAgreesWithFractionalOnThisExampleSinceNoSharedCandidateEverHoldsMixedValues() {
        // Every shared candidate in the Wikipedia example (Andrea, Carter) only ever transfers
        // its own single-valued original ballots -- never a mix of differently-valued arrivals
        // -- which is exactly the case where WIGM and fractional transfer are documented to
        // agree exactly (see WigmTransfer's own javadoc elsewhere in this project). Confirms
        // CpoStvRuleset's transferMethod is actually threaded through the notional count.
        CpoStvRuleset wigmRuleset =
                new CpoStvRuleset(HagenbachBischoffQuota.INSTANCE, WigmTransfer.INSTANCE, Exact.INSTANCE);

        CpoStvResult result = CpoStvCounter.count(CANDIDATES, BALLOTS, 3, wigmRuleset);

        assertThat(result.winners()).containsExactly(Set.of(ANDREA, CARTER, DELILAH));
        Set<Candidate> outcomeA = Set.of(ANDREA, CARTER, DELILAH);
        Set<Candidate> outcomeB = Set.of(ANDREA, CARTER, ERIC);
        assertThat(result.outcomeScores().get(outcomeA).get(outcomeB)).isEqualTo(Fraction.of(79));
        assertThat(result.outcomeScores().get(outcomeB).get(outcomeA)).isEqualTo(Fraction.of(71));
    }

    @Test
    void ballotsWithNoContinuingPreferenceAreExhaustedAndCountForNobody() {
        // 4 candidates, 2 seats, quota = floor(29/3)+1 = 10. D's 5 ballots rank only D -- when D
        // is excluded (in neither outcome being compared), those ballots have no further
        // preference and simply vanish rather than counting toward anyone.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        List<Ballot> ballots = List.of(ballot(10, a), ballot(8, b), ballot(6, c), ballot(5, d));
        CpoStvRuleset ruleset =
                new CpoStvRuleset(DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE);

        CpoStvResult result = CpoStvCounter.count(List.of(a, b, c, d), ballots, 2, ruleset);

        // Neither A (10, exactly at quota, no surplus) nor B/C ever transfers here, so the
        // scores are just the raw, un-transferred tallies -- 10+8=18 and 10+6=16, with D's 5
        // exhausted votes appearing in neither (18+16=34, not 39 -- the 5 are simply gone).
        assertThat(result.outcomeScores().get(Set.of(a, b)).get(Set.of(a, c))).isEqualTo(Fraction.of(18));
        assertThat(result.outcomeScores().get(Set.of(a, c)).get(Set.of(a, b))).isEqualTo(Fraction.of(16));
    }

    @Test
    void aGenuineTieBetweenOutcomesProducesATiedRankingGroup() {
        // 4 candidates, 2 seats, each ballot a single, unrankable-further preference -- nobody
        // ever crosses quota (10 < quota 11), so scores are always just the raw first-preference
        // sums, independent of which two outcomes are being compared: {A,B}=20, {A,C}=15,
        // {A,D}=15, {B,C}=15, {B,D}=15, {C,D}=10. Four different outcomes land on the exact same
        // score (15) and must all tie together in the final ranking.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        List<Ballot> ballots = List.of(ballot(10, a), ballot(10, b), ballot(5, c), ballot(5, d));
        CpoStvRuleset ruleset =
                new CpoStvRuleset(DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE);

        CpoStvResult result = CpoStvCounter.count(List.of(a, b, c, d), ballots, 2, ruleset);

        assertThat(result.outcomeScores().get(Set.of(a, d)).get(Set.of(b, c))).isEqualTo(Fraction.of(15));
        assertThat(result.outcomeScores().get(Set.of(b, c)).get(Set.of(a, d))).isEqualTo(Fraction.of(15));

        assertThat(result.winners()).containsExactly(Set.of(a, b));
        assertThat(result.ranking()).hasSize(3);
        assertThat(result.ranking().get(1)).containsExactlyInAnyOrder(
                Set.of(a, c), Set.of(a, d), Set.of(b, c), Set.of(b, d));
        assertThat(result.ranking().get(2)).containsExactly(Set.of(c, d));
    }

    @Test
    void resolvesAGenuineCycleAmongOutcomesViaBeatpath() {
        // 5 candidates, 2 seats, quota = floor(73/3)+1 = 25 (Droop). Found by randomized search
        // (not hand-constructed) and independently verified by re-implementing this exact
        // algorithm in Python before transcribing it here: outcomes {A,B}, {A,C}, and {C,D} form
        // a genuine cycle in DIRECT pairwise comparison -- {A,B} beats {A,C} (48 vs 25), {A,C}
        // beats {C,D} (54 vs 44), yet {C,D} beats {A,B} (44 vs 29) -- so no single one of these
        // three beats both others outright. The overall count still resolves cleanly to a full
        // strict ranking of all 10 outcomes via strongest path, with {B,C} the overall winner
        // (which never even appears in the cyclic triple).
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        Candidate e = Candidate.of("E");
        List<Ballot> ballots = List.of(
                ballot(19, d, b, e, c, a),
                ballot(16, c, e),
                ballot(29, b, a, c, e, d),
                ballot(9, c, d));
        CpoStvRuleset ruleset =
                new CpoStvRuleset(DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE);

        CpoStvResult result = CpoStvCounter.count(List.of(a, b, c, d, e), ballots, 2, ruleset);

        Set<Candidate> ab = Set.of(a, b);
        Set<Candidate> ac = Set.of(a, c);
        Set<Candidate> cd = Set.of(c, d);
        assertThat(result.outcomeScores().get(ab).get(ac)).isEqualTo(Fraction.of(48));
        assertThat(result.outcomeScores().get(ac).get(ab)).isEqualTo(Fraction.of(25));
        assertThat(result.outcomeScores().get(ac).get(cd)).isEqualTo(Fraction.of(54));
        assertThat(result.outcomeScores().get(cd).get(ac)).isEqualTo(Fraction.of(44));
        assertThat(result.outcomeScores().get(cd).get(ab)).isEqualTo(Fraction.of(44));
        assertThat(result.outcomeScores().get(ab).get(cd)).isEqualTo(Fraction.of(29));

        assertThat(result.winners()).containsExactly(Set.of(b, c));
        assertThat(result.ranking()).hasSize(10);
        assertThat(result.ranking()).allSatisfy(group -> assertThat(group).hasSize(1));
    }

    @Test
    void seatsOneLessThanCandidatesMeansNoOutcomePairEverExcludesAnyoneFromStanding() {
        // 4 candidates, 3 seats -- every pair of outcomes shares 2 of their 3 members and
        // differs in exactly 1, and since any two distinct 3-of-4 subsets union back to all 4
        // candidates, nobody is ever excluded from "standing" in any comparison; only surplus
        // transfer of the shared pair ever matters. Quota = floor(28/4)+1 = 8.
        Candidate a = Candidate.of("A");
        Candidate b = Candidate.of("B");
        Candidate c = Candidate.of("C");
        Candidate d = Candidate.of("D");
        List<Ballot> ballots = List.of(
                ballot(10, a, b, c, d),
                ballot(8, b, c, d, a),
                ballot(6, c, d, a, b),
                ballot(4, d, a, b, c));
        CpoStvRuleset ruleset =
                new CpoStvRuleset(DroopQuota.INSTANCE, FractionalTransfer.INSTANCE, Exact.INSTANCE);

        CpoStvResult result = CpoStvCounter.count(List.of(a, b, c, d), ballots, 3, ruleset);

        // {A,B,C} vs {A,B,D}: shared={A,B}. A's surplus (10-8=2) transfers to B (A's own
        // ballots are all A>B>C>D, next preference after A is B), giving B a live tally of
        // 8+2=10 -- but B is ALSO shared, and is now itself 2 over quota, so B's surplus
        // transfers too (to C, B's own next preference): 8 original B-ballots at value 1 plus
        // 10 A-transferred ballots at value 0.2 each (ratio 2/10=0.2 applied to both parcels)
        // all move to C, landing C at exactly 6+1.6+0.4=8. Final: A=8 (frozen), B=8 (frozen),
        // C=8 (live, unaffected since C isn't shared here), D=4 (untouched). Score({A,B,C}) =
        // 8+8+8=24, Score({A,B,D})=8+8+4=20.
        assertThat(result.outcomeScores().get(Set.of(a, b, c)).get(Set.of(a, b, d))).isEqualTo(Fraction.of(24));
        assertThat(result.outcomeScores().get(Set.of(a, b, d)).get(Set.of(a, b, c))).isEqualTo(Fraction.of(20));
        assertThat(result.ranking()).hasSize(4);
    }

    /**
     * No source defines how CPO-STV should split a tied ballot, so only preprocessing policies
     * apply: under {@code truncate}, a ballot tied at first preference is void, so adding some
     * to the Wikipedia example changes nothing at all -- same scores, same winner.
     */
    @Test
    void truncatedFirstRankTiesAreVoidAndSplitIsRejected() {
        List<Ballot> withTies = new java.util.ArrayList<>(BALLOTS);
        withTies.add(new Ballot(List.of(Set.of(BRAD, ERIC), Set.of(DELILAH)), BigInteger.valueOf(30)));
        CpoStvRuleset truncating = new CpoStvRuleset(HagenbachBischoffQuota.INSTANCE, FractionalTransfer.INSTANCE,
                Exact.INSTANCE, TiedRankingPolicy.TRUNCATE, 0);

        assertThat(CpoStvCounter.count(CANDIDATES, withTies, 3, truncating))
                .isEqualTo(CpoStvCounter.count(CANDIDATES, BALLOTS, 3, RULESET));

        CpoStvRuleset splitting = new CpoStvRuleset(HagenbachBischoffQuota.INSTANCE, FractionalTransfer.INSTANCE,
                Exact.INSTANCE, TiedRankingPolicy.SPLIT, 0);
        assertThatThrownBy(() -> CpoStvCounter.count(CANDIDATES, withTies, 3, splitting))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("CPO-STV has no defined rule for splitting");
    }

    private static Ballot ballot(int count, Candidate... ranking) {
        return new Ballot(List.of(ranking).stream().map(Set::of).toList(), BigInteger.valueOf(count));
    }
}
