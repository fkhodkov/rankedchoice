package info.fkhodkov.rankedchoice.core.util;

import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unmodifiable copies that keep their source's order, for the results a count hands its caller:
 * the counts build their maps and sets in an order the reports print them in (candidates as
 * listed, a ranking's places), which {@link Map#copyOf} and {@link Set#copyOf} don't keep.
 */
public final class Unmodifiable {

    private Unmodifiable() {
    }

    public static <T> List<T> list(List<T> list) {
        return List.copyOf(list);
    }

    /**
     * An unmodifiable copy in the same order; a set this made already is returned as it is, so a
     * set shared by many results (Schulze STV's committee, by every support of it) stays shared.
     */
    public static <T> Set<T> set(Set<T> set) {
        return set instanceof Snapshot<T> snapshot ? snapshot : new Snapshot<>(set);
    }

    public static <K, V> Map<K, V> map(Map<K, V> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /** A list of sets, each set copied too. */
    public static <T> List<Set<T>> listOfSets(List<? extends Set<T>> list) {
        return list.stream().map(Unmodifiable::set).toList();
    }

    /** A map of maps, a matrix by row, each row copied too. */
    public static <K, L, V> Map<K, Map<L, V>> mapOfMaps(Map<K, ? extends Map<L, V>> map) {
        Map<K, Map<L, V>> copy = new LinkedHashMap<>();
        map.forEach((key, row) -> copy.put(key, map(row)));
        return Collections.unmodifiableMap(copy);
    }

    /** A set copied once and never changed: its own type, so {@link #set} can tell it needn't copy it again. */
    private static final class Snapshot<T> extends AbstractSet<T> {

        private final Set<T> elements;

        Snapshot(Set<T> set) {
            this.elements = Collections.unmodifiableSet(new LinkedHashSet<>(set));
        }

        @Override
        public Iterator<T> iterator() {
            return elements.iterator();
        }

        @Override
        public int size() {
            return elements.size();
        }

        @Override
        public boolean contains(Object o) {
            return elements.contains(o);
        }
    }
}
