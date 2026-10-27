package info.fkhodkov.rankedchoice.schulzestv;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * The support {@code N[committee; g]} for committees too large for the closed form's 2^M
 * subsets (see {@link SchulzeStvCounter}), up to 63 members: the minimum over non-empty J of
 * f(J) / |J|, where f(J) is the completed weight preferring some member of J to g.
 *
 * <p>The minimising J is found by Dinkelbach's method, in {@code double}. For a trial value t,
 * the J minimising f(J) - t|J| is a minimum cut in a network from a source to each member
 * (capacity t), each member to every completed voter preferring it to g (unbounded), and each
 * such voter to a sink (their weight): the members left on the source's side are J. Starting
 * from t = f(all members) / M, each cut either shows that no J does better than t or gives one
 * that does, whose ratio is the next t. The support is then computed exactly, by the closed
 * form's own exact completion ({@link SchulzeStvCounter.Completion#exactWithin}), at every J the
 * search visited, and the smallest kept. It is the exact support unless two different sets'
 * ratios come within about 10^-12 of each other, relatively, without being equal, where
 * {@code double} can't tell which is smaller.
 *
 * <p>Proportional completion (section 8.1.1) is listed out here, sparsely: a voter level with g
 * on members T is replaced by the original voters who aren't level with g on all of T, in
 * proportion, each passing on which of T they place above g, and completed in turn for any part
 * of T they're still level on. Where many ballots leave many of a large contest's candidates
 * unranked, that can spread a few voters over a great many sets, and past {@link
 * #MAX_COMPLETED_SETS} the count is refused rather than left running for hours. If nobody tells
 * T and g apart at all, every order of them is equally likely, which spreads the voter over all
 * 2^|T| subsets; that's refused past {@link #MAX_UNDECIDED_TIE} members. Either refusal is a
 * {@link TooSpread}, and the closed form takes such a contest instead where it isn't too large
 * (see {@link SchulzeStvCounter#support(SchulzeStvCounter.Reduced, int)}).
 */
final class LargeCommitteeSupport {

    /** A tie nobody breaks is spread over 2^|T| subsets; past this, the count is refused. */
    static final int MAX_UNDECIDED_TIE = 20;

    /** Past this many completed sets in one support, the count is refused. */
    static final int MAX_COMPLETED_SETS = 4_000_000;

    private final long[] prefers;
    private final long[] ties;
    private final BigInteger[] counts;
    private final double[] weights;
    private final Map<Long, Shares> completions = new HashMap<>();
    private long completedSets;

    private LargeCommitteeSupport(long[] prefers, long[] ties, BigInteger[] counts) {
        this.prefers = prefers;
        this.ties = ties;
        this.counts = counts;
        weights = Arrays.stream(counts).mapToDouble(BigInteger::doubleValue).toArray();
    }

    /**
     * N[the contest's other members; member k], from ballots reduced to the contest ({@code
     * levels[b][j]} is member j's level on group b, {@code counts[b]} its voters).
     */
    static Fraction of(int[][] levels, BigInteger[] counts, int k) {
        int size = levels.length == 0 ? 0 : levels[0].length;
        int seats = size - 1;
        if (seats > Long.SIZE - 1) {
            throw new IllegalArgumentException("at most " + (Long.SIZE - 1) + " members, got " + seats);
        }
        Map<List<Long>, BigInteger> merged = new HashMap<>();
        for (int b = 0; b < levels.length; b++) {
            int[] level = levels[b];
            int against = level[k];
            long prefer = 0;
            long tie = 0;
            for (int j = 0, o = 0; j < size; j++) {
                if (j == k) {
                    continue;
                }
                if (level[j] < against) {
                    prefer |= 1L << o;
                } else if (level[j] == against) {
                    tie |= 1L << o;
                }
                o++;
            }
            merged.merge(List.of(prefer, tie), counts[b], BigInteger::add);
        }
        long[] prefers = new long[merged.size()];
        long[] ties = new long[merged.size()];
        BigInteger[] weights = new BigInteger[merged.size()];
        int i = 0;
        for (Map.Entry<List<Long>, BigInteger> pattern : merged.entrySet()) {
            prefers[i] = pattern.getKey().get(0);
            ties[i] = pattern.getKey().get(1);
            weights[i] = pattern.getValue();
            i++;
        }
        return new LargeCommitteeSupport(prefers, ties, weights).support(seats);
    }

    private Fraction support(int seats) {
        // Completed voters by the members they prefer to g; those preferring none never count.
        LongDoubleMap items = new LongDoubleMap();
        for (int i = 0; i < counts.length; i++) {
            if (ties[i] == 0) {
                if (prefers[i] != 0) {
                    items.add(prefers[i], weights[i]);
                }
                continue;
            }
            Shares shares = completion(ties[i]);
            for (int s = 0; s < shares.size(); s++) {
                long prefer = prefers[i] | shares.key(s);
                if (prefer != 0) {
                    items.add(prefer, weights[i] * shares.value(s));
                }
            }
        }
        long[] sets = Arrays.copyOf(items.keys(), items.size());
        double[] setWeights = Arrays.copyOf(items.values(), items.size());

        long all = (1L << seats) - 1;
        List<Long> visited = new ArrayList<>();
        visited.add(all);
        double scale = Math.max(1, covered(sets, setWeights, all));
        double ratio = covered(sets, setWeights, all) / seats;
        for (int iteration = 0; iteration < 4 * seats + 8; iteration++) {
            long better = MinimumCut.chosen(sets, setWeights, seats, ratio, scale);
            if (better == 0 || visited.contains(better)) {
                break;
            }
            double next = covered(sets, setWeights, better) / Long.bitCount(better);
            visited.add(better);
            if (!(next < ratio)) {
                break;
            }
            ratio = next;
        }

        SchulzeStvCounter.Completion exact = new SchulzeStvCounter.Completion(prefers, ties, counts, seats);
        Fraction total = Fraction.of(exact.total());
        Fraction best = null;
        for (long members : visited) {
            Fraction support = total.subtract(exact.exactWithin(all & ~members))
                    .divide(Fraction.of(Long.bitCount(members)));
            if (best == null || support.compareTo(best) < 0) {
                best = support;
            }
        }
        return best;
    }

    private static double covered(long[] sets, double[] weights, long members) {
        double sum = 0;
        for (int i = 0; i < sets.length; i++) {
            if ((sets[i] & members) != 0) {
                sum += weights[i];
            }
        }
        return sum;
    }

    /**
     * How a voter level with g on {@code tie} is completed, approximately: each subset of {@code
     * tie} that ends up above g, with its share.
     */
    private Shares completion(long tie) {
        Shares known = completions.get(tie);
        if (known != null) {
            return known;
        }
        Map<List<Long>, Double> donors = new HashMap<>();
        double total = 0;
        for (int i = 0; i < counts.length; i++) {
            long theirTie = ties[i] & tie;
            if (theirTie != tie) {
                donors.merge(List.of(prefers[i] & tie, theirTie), weights[i], Double::sum);
                total += weights[i];
            }
        }
        LongDoubleMap shares = new LongDoubleMap();
        if (total > 0) {
            for (Map.Entry<List<Long>, Double> donor : donors.entrySet()) {
                long fixed = donor.getKey().get(0);
                long stillTied = donor.getKey().get(1);
                double share = donor.getValue() / total;
                if (stillTied == 0) {
                    shares.add(fixed, share);
                } else {
                    Shares inner = completion(stillTied);
                    for (int s = 0; s < inner.size(); s++) {
                        shares.add(fixed | inner.key(s), share * inner.value(s));
                    }
                }
            }
        } else {
            int k = Long.bitCount(tie);
            if (k > MAX_UNDECIDED_TIE) {
                throw new TooSpread("no voter tells apart " + (k + 1) + " candidates of one contest; "
                        + "completing that would take 2^" + k + " orders");
            }
            double orders = factorial(k + 1);
            for (long a = 0; a < 1L << k; a++) {
                int s = Long.bitCount(a);
                shares.add(Long.expand(a, tie), factorial(s) * factorial(k - s) / orders);
            }
        }
        completedSets += shares.size();
        if (completedSets > MAX_COMPLETED_SETS) {
            throw new TooSpread("proportional completion of a contest of " + (Long.bitCount(tie) + 1)
                    + " or more tied candidates spreads its voters over more than " + MAX_COMPLETED_SETS
                    + " orders: too many ballots leave too many candidates unranked to complete");
        }
        Shares compact = shares.compact(tie);
        completions.put(tie, compact);
        return compact;
    }

    private static double factorial(int n) {
        double result = 1;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    /** A contest whose completion would be too large to list out. */
    static final class TooSpread extends IllegalStateException {
        TooSpread(String message) {
            super(message);
        }
    }

    /** The minimum cut for one trial ratio, in {@code double}. */
    static final class MinimumCut {

        private MinimumCut() {
        }

        /**
         * The members J minimising f(J) - ratio * |J|, or 0 (the empty set) if none brings it
         * below zero by more than rounding error relative to {@code scale}.
         */
        static long chosen(long[] sets, double[] weights, int seats, double ratio, double scale) {
            int items = sets.length;
            // Nodes: 0 the source, 1..seats the members, then the items, then the sink.
            int sink = seats + items + 1;
            Network network = new Network(sink + 1, scale * 1e-13);
            for (int j = 0; j < seats; j++) {
                network.add(0, 1 + j, ratio);
            }
            for (int i = 0; i < items; i++) {
                long set = sets[i];
                while (set != 0) {
                    int j = Long.numberOfTrailingZeros(set);
                    set &= set - 1;
                    network.add(1 + j, 1 + seats + i, Double.POSITIVE_INFINITY);
                }
                network.add(1 + seats + i, sink, weights[i]);
            }
            double cut = network.maximumFlow(0, sink);
            // f(J) - ratio |J| = cut - ratio * seats: J does better only if that's below zero.
            if (cut >= ratio * seats - scale * 1e-12) {
                return 0;
            }
            boolean[] reached = network.reachableFrom(0);
            long chosen = 0;
            for (int j = 0; j < seats; j++) {
                if (reached[1 + j]) {
                    chosen |= 1L << j;
                }
            }
            return chosen;
        }
    }

    /** Dinic's maximum flow, in {@code double}, treating residuals up to {@code epsilon} as none. */
    private static final class Network {
        private final int[] head;
        private int[] next = new int[16];
        private int[] to = new int[16];
        private double[] residual = new double[16];
        private int edges;
        private final int[] depth;
        private final int[] current;
        private final double epsilon;

        Network(int nodes, double epsilon) {
            head = new int[nodes];
            Arrays.fill(head, -1);
            depth = new int[nodes];
            current = new int[nodes];
            this.epsilon = epsilon;
        }

        void add(int from, int target, double capacity) {
            link(from, target, capacity);
            link(target, from, 0);
        }

        private void link(int from, int target, double capacity) {
            if (edges == to.length) {
                next = Arrays.copyOf(next, edges * 2);
                to = Arrays.copyOf(to, edges * 2);
                residual = Arrays.copyOf(residual, edges * 2);
            }
            to[edges] = target;
            residual[edges] = capacity;
            next[edges] = head[from];
            head[from] = edges++;
        }

        double maximumFlow(int source, int sink) {
            double flow = 0;
            while (levels(source, sink)) {
                System.arraycopy(head, 0, current, 0, head.length);
                double pushed;
                while ((pushed = push(source, sink, Double.POSITIVE_INFINITY)) > 0) {
                    flow += pushed;
                }
            }
            return flow;
        }

        private boolean levels(int source, int sink) {
            Arrays.fill(depth, -1);
            depth[source] = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            while (!queue.isEmpty()) {
                int node = queue.poll();
                for (int e = head[node]; e >= 0; e = next[e]) {
                    if (depth[to[e]] < 0 && residual[e] > epsilon) {
                        depth[to[e]] = depth[node] + 1;
                        queue.add(to[e]);
                    }
                }
            }
            return depth[sink] >= 0;
        }

        private double push(int node, int sink, double limit) {
            if (node == sink) {
                return limit;
            }
            for (; current[node] >= 0; current[node] = next[current[node]]) {
                int e = current[node];
                if (residual[e] <= epsilon || depth[to[e]] != depth[node] + 1) {
                    continue;
                }
                double pushed = push(to[e], sink, Math.min(limit, residual[e]));
                if (pushed > 0) {
                    residual[e] -= pushed;
                    residual[e ^ 1] += pushed;
                    return pushed;
                }
            }
            return 0;
        }

        boolean[] reachableFrom(int source) {
            boolean[] reached = new boolean[head.length];
            reached[source] = true;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            while (!queue.isEmpty()) {
                int node = queue.poll();
                for (int e = head[node]; e >= 0; e = next[e]) {
                    if (!reached[to[e]] && residual[e] > epsilon) {
                        reached[to[e]] = true;
                        queue.add(to[e]);
                    }
                }
            }
            return reached;
        }
    }
}
