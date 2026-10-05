package info.fkhodkov.rankedchoice.schulzestv;

/**
 * How a voter level with an outsider on some members is completed: each set of those members
 * placed above the outsider, by key, with its share. Sparse ({@link LongDoubleMap}) or, when it
 * reaches most of the subsets, {@link Dense}.
 */
interface Shares {

    int size();

    long key(int i);

    double value(int i);

    /** Every subset of {@code tie}, entry a for the members {@code Long.expand(a, tie)}. */
    record Dense(long tie, double[] shares) implements Shares {

        @Override
        public int size() {
            return shares.length;
        }

        @Override
        public long key(int i) {
            return Long.expand(i, tie);
        }

        @Override
        public double value(int i) {
            return shares[i];
        }
    }
}
