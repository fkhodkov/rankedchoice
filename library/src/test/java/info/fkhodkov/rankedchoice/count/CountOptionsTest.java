package info.fkhodkov.rankedchoice.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CountOptionsTest {

    /** Every option, in the class's order, with a value of its type. */
    private static Map<String, Object> everyOption() {
        Map<String, Object> options = new LinkedHashMap<>();
        for (Field field : CountOptions.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                Class<?> type = field.getType();
                options.put(field.getName(), type == Integer.class ? (Object) (100 + options.size())
                        : type == List.class ? List.of(field.getName() + "-value") : field.getName() + "-value");
            }
        }
        return options;
    }

    private static CountOptions with(CountOptions options, String option, Object value) throws Exception {
        String name = "with" + Character.toUpperCase(option.charAt(0)) + option.substring(1);
        for (Method with : CountOptions.class.getMethods()) {
            if (with.getName().equals(name)) {
                return (CountOptions) with.invoke(options, value);
            }
        }
        throw new AssertionError("no " + name);
    }

    private static Object get(CountOptions options, String option) throws Exception {
        return CountOptions.class.getMethod(option).invoke(options);
    }

    @Test
    void eachWithSetsItsOwnOptionAlone() throws Exception {
        Map<String, Object> every = everyOption();
        assertThat(every).hasSize(12);
        for (Map.Entry<String, Object> option : every.entrySet()) {
            CountOptions options = with(CountOptions.NONE, option.getKey(), option.getValue());
            for (String other : every.keySet()) {
                assertThat(get(options, other)).as(option.getKey() + " then " + other)
                        .isEqualTo(other.equals(option.getKey()) ? option.getValue() : null);
            }
        }
    }

    @Test
    void optionsAreValues() throws Exception {
        CountOptions options = CountOptions.NONE;
        for (Map.Entry<String, Object> option : everyOption().entrySet()) {
            options = with(options, option.getKey(), option.getValue());
        }
        CountOptions same = CountOptions.NONE.withMethod("method-value").withRuleset("ruleset-value").withSeats(102)
                .withQuota("quota-value").withTransfer("transfer-value").withRounding("rounding-value")
                .withTiebreak("tiebreak-value").withTiedRankings("tiedRankings-value").withMinimumPreferences(108)
                .withPrecision(109).withBordaScoring("bordaScoring-value").withGivenList(List.of("givenList-value"));

        assertThat(options).isEqualTo(same).hasSameHashCodeAs(same).hasToString(same.toString())
                .isNotEqualTo(same.withSeats(3));
        assertThat(options.toString()).startsWith("CountOptions[method=method-value, ruleset=ruleset-value, seats=102")
                .endsWith("givenList=[givenList-value]]");
        assertThat(CountOptions.NONE.withSeats(3).withSeats(null)).isEqualTo(CountOptions.NONE);
    }

    @Test
    void keepsACopyOfTheGivenList() {
        List<String> names = new ArrayList<>(List.of("Delilah", "Carter"));
        CountOptions options = CountOptions.NONE.withGivenList(names);
        names.clear();

        assertThat(options.givenList()).containsExactly("Delilah", "Carter");
        assertThatThrownBy(() -> options.givenList().add("Brad")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNullInTheGivenListIsRefusedAsNoCandidateLikeAnyOtherName() {
        // As a JSON request's "givenList": [null] gives it: refused by the count, not by a NullPointerException.
        CountOptions options = CountOptions.NONE.withMethod("schulze-proportional")
                .withGivenList(Arrays.asList("Delilah", null));
        Election election = Election.read(
                Path.of("src/test/resources/testdata/wikipedia/wikipedia-andrea-brad-carter-delilah.blt"), null);

        assertThat(options.givenList()).containsExactly("Delilah", null);
        assertThatThrownBy(() -> Tabulator.of(options, null).count(election, warning -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--given-list names null, who isn't a candidate");
    }
}
