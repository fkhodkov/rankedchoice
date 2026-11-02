package info.fkhodkov.rankedchoice.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.config.ElectionConfig;

class RulesetsTest {

    @Test
    void loadsTheBundledScottishRuleset() {
        ElectionConfig config = Rulesets.load("scottish");

        assertThat(config.quota()).isEqualTo("droop");
        assertThat(config.transfer()).isEqualTo("wigm");
        assertThat(config.rounding()).isEqualTo("truncate:5");
        assertThat(config.tiebreak()).isEqualTo("previous-round,declared");
    }

    @Test
    void loadsTheBundledErs97Ruleset() {
        ElectionConfig config = Rulesets.load("ers97");

        assertThat(config.quota()).isEqualTo("droop-rounded:2");
        assertThat(config.transfer()).isEqualTo("last-parcel");
        assertThat(config.rounding()).isEqualTo("truncate:2");
    }

    @Test
    void loadsTheBundledErs76Ruleset() {
        ElectionConfig config = Rulesets.load("ers76");

        assertThat(config.quota()).isEqualTo("droop-rounded:2");
        assertThat(config.transfer()).isEqualTo("last-parcel");
        assertThat(config.rounding()).isEqualTo("truncate:2");
    }

    @Test
    void loadsTheBundledHareClarkQpqRuleset() {
        ElectionConfig config = Rulesets.load("hare-clark-qpq");

        assertThat(config.quota()).isEqualTo("droop");
        assertThat(config.transfer()).isEqualTo("unweighted-exclusive:6");
        assertThat(config.rounding()).isEqualTo("truncate:0");
        assertThat(config.surplusOrder()).isEqualTo("election");
    }

    @Test
    void loadsTheBundledActHareClarkRuleset() {
        ElectionConfig config = Rulesets.load("act-hare-clark");

        assertThat(config.quota()).isEqualTo("droop");
        assertThat(config.transfer()).isEqualTo("last-parcel-deferred-rounding");
        assertThat(config.rounding()).isEqualTo("truncate:6");
    }

    @Test
    void refusesAnUnknownRulesetListingThemAll() {
        assertThatThrownBy(() -> Rulesets.load("does-not-exist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Unknown ruleset 'does-not-exist'.\nAvailable rulesets:\n  scottish ");
    }

    @Test
    void everyRulesetLoads() {
        for (Rulesets.Ruleset ruleset : Rulesets.ALL) {
            assertThat(Rulesets.load(ruleset.name()).quota()).as(ruleset.name()).isNotNull();
        }
    }
}
