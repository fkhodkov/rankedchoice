package info.fkhodkov.rankedchoice.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UnmodifiableTest {

    @Test
    void aSetIsCopiedInOrderOnceAndThenKept() {
        Set<String> source = new LinkedHashSet<>(List.of("c", "a", "b"));
        Set<String> copy = Unmodifiable.set(source);

        source.clear();

        assertThat(copy).containsExactly("c", "a", "b").isEqualTo(Set.of("a", "b", "c"));
        assertThat(copy.hashCode()).isEqualTo(Set.of("a", "b", "c").hashCode());
        assertThat(Unmodifiable.set(copy)).isSameAs(copy);
        assertThatThrownBy(() -> copy.add("d")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> copy.iterator().remove()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(copy::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aMapOfMapsIsCopiedInOrderRowByRow() {
        Map<String, Map<String, Integer>> source = new LinkedHashMap<>();
        source.put("b", new LinkedHashMap<>(Map.of("a", 1)));
        source.put("a", new LinkedHashMap<>(Map.of("b", 2)));
        Map<String, Map<String, Integer>> copy = Unmodifiable.mapOfMaps(source);

        source.get("b").put("a", 5);

        assertThat(copy.keySet()).containsExactly("b", "a");
        assertThat(copy.get("b")).containsExactly(Map.entry("a", 1));
        assertThatThrownBy(() -> copy.get("b").clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
