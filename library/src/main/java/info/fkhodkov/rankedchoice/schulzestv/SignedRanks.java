package info.fkhodkov.rankedchoice.schulzestv;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.core.util.Interruption;

/**
 * Exact fractions as {@code int}s in the same order: zero as 0, and every other value as its
 * sign times one more than its size's rank among the values' distinct sizes. So the order is
 * kept exactly, ties included, and a value's negative is the negative of its key, as it would
 * be among the values' negatives.
 *
 * <p>Schulze STV's beatpaths compare margins and nothing else, and compared as fractions they
 * took most of the time after the supports: about 10 of the 24 s of 24 candidates for 5 seats
 * on 10,000 voters. The sizes are sorted by {@code double} first; only those whose
 * approximations lie within {@link #TOLERANCE} of each other, where rounding could have put
 * them out of order or made different sizes equal, are then sorted exactly.
 *
 * <p>The values needn't be held all at once: {@link #of(int[], Values)} asks for each when it
 * needs it, once for its approximation and sign and again only if its cluster is sorted exactly.
 * Schulze STV's 2 million margins for 24 candidates and 5 seats, held as fractions, took about
 * 340 MB.
 */
final class SignedRanks {

    /**
     * Relative, far wider than the few units of rounding in {@link #approximate}; the absolute
     * part covers sizes too small for a {@code double}, approximated as 0.
     */
    private static final double TOLERANCE = 1e-12;
    private static final double TINY = 1e-250;

    private SignedRanks() {
    }

    /** The value at a row's {@code index}, made when asked for, from any thread. */
    @FunctionalInterface
    interface Values {
        Fraction at(int row, int index);
    }

    static int[][] of(Fraction[][] values) {
        return of(Arrays.stream(values).mapToInt(row -> row.length).toArray(), (r, i) -> values[r][i]);
    }

    /** The keys of rows of {@code lengths[r]} values each, asked of {@code values}. */
    static int[][] of(int[] lengths, Values values) {
        int rows = lengths.length;
        int[] offsets = new int[rows + 1];
        for (int r = 0; r < rows; r++) {
            offsets[r + 1] = Math.addExact(offsets[r], lengths[r]);
        }
        int[] keys = keys(offsets[rows], i -> {
            // The last row starting at or before i: the one holding it, past any empty rows.
            int low = 0;
            int high = rows - 1;
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (offsets[middle] <= i) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            return values.at(low, i - offsets[low]);
        });
        int[][] ranked = new int[rows][];
        for (int r = 0; r < rows; r++) {
            ranked[r] = Arrays.copyOfRange(keys, offsets[r], offsets[r + 1]);
        }
        return ranked;
    }

    private static int[] keys(int total, IntFunction<Fraction> values) {
        double[] sizes = new double[total];
        byte[] signs = new byte[total];
        Interruption.Watch watch = Interruption.watch();
        inChunks(total, 1 << 16, watch, i -> {
            Fraction value = values.apply(i);
            signs[i] = (byte) value.signum();
            sizes[i] = Math.abs(approximate(value));
        });
        double[] distinct = sizes.clone();
        Arrays.parallelSort(distinct);
        int d = 0;
        for (int j = 0; j < total; j++) {
            if (d == 0 || distinct[j] != distinct[d - 1]) {
                distinct[d++] = distinct[j];
            }
        }
        // Clusters: runs of approximations each within the tolerance of the one before.
        int[] clusterOf = new int[d];
        int clusters = d == 0 ? 0 : 1;
        for (int j = 1; j < d; j++) {
            if (distinct[j] - distinct[j - 1] > TOLERANCE * distinct[j] + TINY) {
                clusters++;
            }
            clusterOf[j] = clusters - 1;
        }
        int[] cluster = new int[total];
        int distinctCount = d;
        inChunks(total, 1 << 16, watch, i ->
                cluster[i] = clusterOf[Arrays.binarySearch(distinct, 0, distinctCount, sizes[i])]);

        // The values of each cluster together, then ranked exactly within it.
        int[] start = new int[clusters + 1];
        for (int c : cluster) {
            start[c + 1]++;
        }
        for (int c = 0; c < clusters; c++) {
            start[c + 1] += start[c];
        }
        int[] members = new int[total];
        int[] filled = Arrays.copyOf(start, clusters);
        for (int i = 0; i < total; i++) {
            members[filled[cluster[i]]++] = i;
        }
        int[] within = new int[total];
        int[] sizesIn = new int[clusters];
        inChunks(clusters, 1 << 8, watch, c -> {
            int from = start[c];
            int to = start[c + 1];
            if (to - from == 1) {
                sizesIn[c] = 1;
                return;
            }
            // The cluster's sizes, made again: a zero is known from its sign.
            Fraction[] size = new Fraction[to - from];
            Integer[] order = new Integer[to - from];
            for (int i = from; i < to; i++) {
                int member = members[i];
                size[i - from] = signs[member] == 0 ? Fraction.ZERO : abs(values.apply(member));
                order[i - from] = i - from;
            }
            Comparator<Integer> bySize = (a, b) -> size[a].compareTo(size[b]);
            Arrays.sort(order, bySize);
            int rank = 0;
            within[members[from + order[0]]] = 0;
            for (int i = 1; i < order.length; i++) {
                if (bySize.compare(order[i - 1], order[i]) != 0) {
                    rank++;
                }
                within[members[from + order[i]]] = rank;
            }
            sizesIn[c] = rank + 1;
        });
        int[] base = new int[clusters];
        for (int c = 1; c < clusters; c++) {
            base[c] = Math.addExact(base[c - 1], sizesIn[c - 1]);
        }
        int[] keys = new int[total];
        for (int i = 0; i < total; i++) {
            keys[i] = signs[i] * Math.addExact(1, base[cluster[i]] + within[i]);
        }
        return keys;
    }

    /**
     * {@code body} for every index below {@code total}, in parallel by chunks of {@code chunk}, each
     * a piece of {@code watch}'s: a piece per index would cost more than many of the bodies do.
     */
    private static void inChunks(int total, int chunk, Interruption.Watch watch, IntConsumer body) {
        int chunks = (int) (((long) total + chunk - 1) / chunk);
        watch.run(() -> IntStream.range(0, chunks).parallel().forEach(k -> {
            try (Interruption.Piece _ = watch.enter()) {
                int end = (int) Math.min(total, (long) (k + 1) * chunk);
                for (int i = k * chunk; i < end; i++) {
                    body.accept(i);
                }
            }
        }));
    }

    private static Fraction abs(Fraction value) {
        return value.signum() < 0 ? value.negate() : value;
    }

    /**
     * A fraction as a {@code double}, within a few units of rounding, even when its numerator
     * and denominator are too large for one: each is cut to its top 64 bits, and the shifts
     * put back in the exponent.
     */
    static double approximate(Fraction value) {
        BigInteger numerator = value.numerator();
        BigInteger denominator = value.denominator();
        int up = Math.max(0, numerator.bitLength() - Long.SIZE);
        int down = Math.max(0, denominator.bitLength() - Long.SIZE);
        double quotient = numerator.shiftRight(up).doubleValue() / denominator.shiftRight(down).doubleValue();
        return Math.scalb(quotient, up - down);
    }
}
