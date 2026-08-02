package info.fkhodkov.rankedchoice.stv.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.quota.DroopQuota;
import info.fkhodkov.rankedchoice.stv.quota.HagenbachBischoffQuota;
import info.fkhodkov.rankedchoice.stv.quota.HareQuota;
import info.fkhodkov.rankedchoice.stv.quota.ImperialiQuota;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.rounding.TruncateDecimal;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
import info.fkhodkov.rankedchoice.stv.tiebreak.FirstPreferenceCountTieBreak;
import info.fkhodkov.rankedchoice.stv.tiebreak.PreviousRoundVotesTieBreak;
import info.fkhodkov.rankedchoice.stv.tiebreak.SeededRandomTieBreak;
import info.fkhodkov.rankedchoice.stv.transfer.FractionalTransfer;
import info.fkhodkov.rankedchoice.stv.transfer.WigmTransfer;
import java.util.List;
import org.junit.jupiter.api.Test;

class StvRulesetParserTest {

    private static final List<Candidate> ORDER = List.of(Candidate.of("A"), Candidate.of("B"));

    @Test
    void parsesEachQuota() {
        assertThat(StvRulesetParser.parseQuota("droop")).isEqualTo(DroopQuota.INSTANCE);
        assertThat(StvRulesetParser.parseQuota("HARE")).isEqualTo(HareQuota.INSTANCE);
        assertThat(StvRulesetParser.parseQuota("hagenbach-bischoff")).isEqualTo(HagenbachBischoffQuota.INSTANCE);
        assertThat(StvRulesetParser.parseQuota("imperiali")).isEqualTo(ImperialiQuota.INSTANCE);
        assertThat(StvRulesetParser.parseQuota(null)).isEqualTo(DroopQuota.INSTANCE);
    }

    @Test
    void rejectsUnknownQuota() {
        assertThatThrownBy(() -> StvRulesetParser.parseQuota("nonsense"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesEachTransferMethod() {
        assertThat(StvRulesetParser.parseTransfer("fractional")).isEqualTo(FractionalTransfer.INSTANCE);
        assertThat(StvRulesetParser.parseTransfer("WIGM")).isEqualTo(WigmTransfer.INSTANCE);
        assertThat(StvRulesetParser.parseTransfer(null)).isEqualTo(FractionalTransfer.INSTANCE);
    }

    @Test
    void parsesRoundingRules() {
        assertThat(StvRulesetParser.parseRounding("exact")).isEqualTo(Exact.INSTANCE);
        assertThat(StvRulesetParser.parseRounding(null)).isEqualTo(Exact.INSTANCE);
        assertThat(StvRulesetParser.parseRounding("truncate:5")).isEqualTo(new TruncateDecimal(5));
    }

    @Test
    void rejectsMalformedTruncate() {
        assertThatThrownBy(() -> StvRulesetParser.parseRounding("truncate:abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StvRulesetParser.parseRounding("nonsense"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesDeclaredOrderTieBreakByDefault() {
        assertThat(StvRulesetParser.parseTieBreak(null, ORDER)).isEqualTo(new DeclaredOrderTieBreak(ORDER));
        assertThat(StvRulesetParser.parseTieBreak("declared", ORDER)).isEqualTo(new DeclaredOrderTieBreak(ORDER));
    }

    @Test
    void parsesFallbackChain() {
        var rule = StvRulesetParser.parseTieBreak("previous-round,first-preference,declared", ORDER);
        assertThat(rule).isEqualTo(new PreviousRoundVotesTieBreak(
                new FirstPreferenceCountTieBreak(new DeclaredOrderTieBreak(ORDER))));
    }

    @Test
    void parsesSeededRandom() {
        var rule = StvRulesetParser.parseTieBreak("seeded-random:42", ORDER);
        assertThat(rule).isInstanceOf(SeededRandomTieBreak.class);
    }

    @Test
    void rejectsChainEndingInARuleThatNeedsAFallback() {
        assertThatThrownBy(() -> StvRulesetParser.parseTieBreak("previous-round", ORDER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fallback");
    }

    @Test
    void rejectsUnknownTieBreakToken() {
        assertThatThrownBy(() -> StvRulesetParser.parseTieBreak("nonsense", ORDER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseBuildsAFullRuleset() {
        var ruleset = StvRulesetParser.parse("hare", "wigm", "truncate:5", "declared", ORDER);
        assertThat(ruleset.quota()).isEqualTo(HareQuota.INSTANCE);
        assertThat(ruleset.transferMethod()).isEqualTo(WigmTransfer.INSTANCE);
        assertThat(ruleset.roundingRule()).isEqualTo(new TruncateDecimal(5));
        assertThat(ruleset.tieBreak()).isEqualTo(new DeclaredOrderTieBreak(ORDER));
    }
}
