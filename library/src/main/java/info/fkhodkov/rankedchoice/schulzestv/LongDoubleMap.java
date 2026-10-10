package info.fkhodkov.rankedchoice.schulzestv;

import java.util.Arrays;

/**
 * A growable map from {@code long} keys to summed {@code double} values, by open addressing:
 * the sparse form of a completion's shares (see {@link Shares}).
 */
final class LongDoubleMap implements Shares {
    private long[] slotKeys = new long[16];
    private int[] slots = new int[16];
    private long[] keys = new long[8];
    private double[] values = new double[8];
    private int size;

    LongDoubleMap() {
        Arrays.fill(slots, -1);
    }

    void add(long key, double value) {
        int at = find(key);
        if (slots[at] >= 0) {
            values[slots[at]] += value;
            return;
        }
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            values = Arrays.copyOf(values, size * 2);
        }
        keys[size] = key;
        values[size] = value;
        slotKeys[at] = key;
        slots[at] = size++;
        if (size * 2 > slots.length) {
            rehash();
        }
    }

    private int find(long key) {
        int mask = slots.length - 1;
        int at = (int) ((key * 0x9E3779B97F4A7C15L) >>> 33) & mask;
        while (slots[at] >= 0 && slotKeys[at] != key) {
            at = (at + 1) & mask;
        }
        return at;
    }

    private void rehash() {
        slotKeys = new long[slots.length * 2];
        slots = new int[slots.length * 2];
        Arrays.fill(slots, -1);
        for (int i = 0; i < size; i++) {
            int at = find(keys[i]);
            slotKeys[at] = keys[i];
            slots[at] = i;
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public long key(int i) {
        return keys[i];
    }

    @Override
    public double value(int i) {
        return values[i];
    }

    long[] keys() {
        return keys;
    }

    double[] values() {
        return values;
    }

    /**
     * These shares as compactly as they'll go: as they are, or, over the subsets of {@code tie}
     * when they reach enough of them that an array is smaller, as one.
     */
    Shares compact(long tie) {
        int k = Long.bitCount(tie);
        if (k > 24 || (long) size * 6 < 1L << k) {
            return this;
        }
        double[] dense = new double[1 << k];
        for (int i = 0; i < size; i++) {
            dense[(int) Long.compress(keys[i], tie)] += values[i];
        }
        return new Shares.Dense(tie, dense);
    }
}
