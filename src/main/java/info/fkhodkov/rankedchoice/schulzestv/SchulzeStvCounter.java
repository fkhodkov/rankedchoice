package info.fkhodkov.rankedchoice.schulzestv;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.IntStream;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.schulze.SchulzeMethod;
import info.fkhodkov.rankedchoice.schulze.SchulzeResult;

/**
 * Runs a count by Schulze STV (Markus Schulze, "The Schulze Method of Voting",
 * arXiv:1804.02973, section 8.1). Like CPO-STV it compares whole committees, but only those
 * that differ by one candidate, and it measures each comparison by how well the voters could be
 * split into separate quotas.
 *
 * <p><b>Support.</b> For a committee {a1..aM} and an outsider g, {@code N[{a1..aM}; g]} is the
 * largest t such that the voters can be split into M disjoint groups, group j preferring aj to
 * g, each weighing at least t. A transportation-problem argument gives it in closed form, the
 * minimum over every non-empty J of {1..M} of (the weight of voters preferring some aj, j in
 * J, to g) / |J|. That's computed exactly over the 2^M subsets; no optimisation solver is
 * needed.
 *
 * <p><b>Proportional completion.</b> The support is defined for voters who cast a strict order,
 * so a voter who ranks some members level with g, unranked candidates included, is replaced
 * proportionally. Their weight is shared by the original voters who are not indifferent among
 * that tied group, each passing on their own order within it. If there are none, it is shared
 * equally over every order of the group (section 8.1.1). This is done separately for each
 * contest of M+1 candidates, over those candidates only, as Schulze's own program does; that
 * reading reproduces the strengths he publishes for Tideman's elections exactly.
 *
 * <p><b>Links and paths.</b> Committees X and Y that differ by one candidate, X = Z + b and
 * Y = Z + c, are joined by a link X to Y of strength (N[X; c], N[Y; b]). Links are compared by
 * margin, N[X; c] - N[Y; b], the relation the paper's worked example uses. A path is as strong
 * as its weakest link, and X beats Y if X's strongest path to Y is stronger than Y's to X.
 * The committees nobody beats are the potential winners. If there are several, Stage 4 breaks
 * the tie with the single-winner Schulze ranking ({@link SchulzeMethod}): A disqualifies B if
 * some candidate in A but not B is ranked above every candidate in B but not A.
 *
 * <p><b>Finding the potential winners.</b> Since "beats" is transitive, there's no need for a
 * strongest path between every pair of committees. Starting from the committee of the
 * single-winner ranking's top M, the count moves to a committee that beats the current one
 * until none does. The committees it doesn't beat and that don't beat it are the only other
 * candidates for potential winner, and each is checked the same way. Each check is two
 * Dijkstra-style widest-path searches, from and to one committee.
 *
 * <p>Cost grows with {@code C(candidates, seats + 1)} contests, each needing M+1 supports
 * over 2^M subsets, and with how many distinct orders the voters give each contest's
 * candidates. Memory grows with the number of links, about M * (candidates - M) per
 * committee, a link and its reverse sharing one exact margin: 18 candidates for 7 seats, about
 * the most links the cap allows (2.45 million), count in 320 MB of heap. {@link
 * #MAX_COMMITTEES} and {@link #MAX_SEATS} cap the election rather than let it run for hours.
 */
public final class SchulzeStvCounter {

    /** See the class javadoc's note on cost. */
    public static final int MAX_COMMITTEES = 50_000;

    /** Each support takes the minimum over 2^seats subsets. */
    public static final int MAX_SEATS = 20;

    private SchulzeStvCounter() {
    }

    public static SchulzeStvResult count(List<Candidate> candidates, List<Ballot> ballots, int seats) {
        int n = candidates.size();
        validate(n, seats);
        SchulzeResult ranking = SchulzeMethod.count(candidates, ballots);
        if (seats == n) {
            Set<Candidate> everyone = Set.copyOf(candidates);
            return new SchulzeStvResult(List.of(), List.of(everyone), List.of(everyone), ranking);
        }

        Binomials binomials = new Binomials(n, seats + 1);
        List<int[]> committees = combinations(n, seats, binomials);
        List<int[]> contests = combinations(n, seats + 1, binomials);
        IndexedBallots indexed = IndexedBallots.of(candidates, ballots);

        // supports[s][k]: N[contest s without its k-th member; that member]. Contests are
        // independent and every figure is exact, so they're computed in parallel.
        Fraction[][] supports = contests.parallelStream()
                .map(contest -> contestSupports(contest, indexed, seats))
                .toArray(Fraction[][]::new);

        Graph graph = Graph.of(committees, supports, n, seats, binomials);
        int start = binomials.rank(topCommittee(candidates, ranking, seats));
        List<Integer> potential = graph.unbeaten(start);

        int[] place = places(candidates, ranking);
        List<Integer> winners = new ArrayList<>();
        for (int a : potential) {
            boolean disqualified = false;
            for (int b : potential) {
                if (a != b && disqualifies(committees.get(b), committees.get(a), place, n)) {
                    disqualified = true;
                    break;
                }
            }
            if (!disqualified) {
                winners.add(a);
            }
        }

        // One set per committee, shared by its supports against every outsider.
        List<Set<Candidate>> committeeSets = toSets(IntStream.range(0, committees.size()).boxed().toList(),
                committees, candidates);
        List<SchulzeStvResult.Support> supportList = new ArrayList<>(contests.size() * (seats + 1));
        int[] committee = new int[seats];
        for (int s = 0; s < contests.size(); s++) {
            int[] contest = contests.get(s);
            for (int k = 0; k <= seats; k++) {
                remove(contest, k, committee);
                supportList.add(new SchulzeStvResult.Support(committeeSets.get(binomials.rank(committee)),
                        candidates.get(contest[k]), supports[s][k]));
            }
        }
        return new SchulzeStvResult(supportList, potential.stream().map(committeeSets::get).toList(),
                winners.stream().map(committeeSets::get).toList(), ranking);
    }

    private static void validate(int candidates, int seats) {
        if (seats <= 0) {
            throw new IllegalArgumentException("seats must be positive, got: " + seats);
        }
        if (seats > candidates) {
            throw new IllegalArgumentException(
                    "cannot fill " + seats + " seat(s) with only " + candidates + " candidate(s)");
        }
        if (seats > MAX_SEATS && seats < candidates) {
            throw new IllegalArgumentException(seats + " seats exceeds this implementation's cap of " + MAX_SEATS
                    + " -- each support is a minimum over 2^seats subsets of the committee");
        }
        BigInteger committees = Binomials.exact(candidates, seats);
        if (committees.compareTo(BigInteger.valueOf(MAX_COMMITTEES)) > 0) {
            throw new IllegalArgumentException("C(" + candidates + ", " + seats + ") = " + committees
                    + " possible committees exceeds this implementation's cap of " + MAX_COMMITTEES
                    + " -- Schulze STV compares every committee with its neighbours, so this "
                    + "candidate/seat combination isn't practical to run exhaustively");
        }
    }

    /** The first {@code seats} candidates of the single-winner ranking, declared order breaking ties. */
    private static int[] topCommittee(List<Candidate> candidates, SchulzeResult ranking, int seats) {
        int[] top = new int[seats];
        int filled = 0;
        for (Set<Candidate> group : ranking.ranking()) {
            for (int c = 0; c < candidates.size() && filled < seats; c++) {
                if (group.contains(candidates.get(c))) {
                    top[filled++] = c;
                }
            }
        }
        Arrays.sort(top);
        return top;
    }

    /** Each candidate's place in the single-winner ranking: equal places for a tie. */
    private static int[] places(List<Candidate> candidates, SchulzeResult ranking) {
        int[] place = new int[candidates.size()];
        int group = 0;
        for (Set<Candidate> tied : ranking.ranking()) {
            for (int c = 0; c < candidates.size(); c++) {
                if (tied.contains(candidates.get(c))) {
                    place[c] = group;
                }
            }
            group++;
        }
        return place;
    }

    /** Stage 4: some a in A but not B is ranked above every b in B but not A. */
    private static boolean disqualifies(int[] a, int[] b, int[] place, int n) {
        boolean[] inA = new boolean[n];
        boolean[] inB = new boolean[n];
        for (int x : a) {
            inA[x] = true;
        }
        for (int x : b) {
            inB[x] = true;
        }
        for (int x : a) {
            if (inB[x]) {
                continue;
            }
            boolean aboveAll = true;
            for (int y : b) {
                if (!inA[y] && place[x] >= place[y]) {
                    aboveAll = false;
                    break;
                }
            }
            if (aboveAll) {
                return true;
            }
        }
        return false;
    }

    private static List<Set<Candidate>> toSets(List<Integer> indices, List<int[]> committees,
            List<Candidate> candidates) {
        List<Set<Candidate>> sets = new ArrayList<>();
        for (int index : indices) {
            Set<Candidate> set = new LinkedHashSet<>();
            for (int c : committees.get(index)) {
                set.add(candidates.get(c));
            }
            sets.add(set);
        }
        return sets;
    }

    // ---- supports ----------------------------------------------------------------------

    /**
     * Every distinct ballot once, as each candidate's position on it (equal positions for a
     * tie, {@link Integer#MAX_VALUE} for unranked: tied below everyone ranked), with how many
     * voters cast it. Blank ballots stay: proportional completion shares them out like any
     * other indifference. {@code smallCounts} holds the same counts as {@code long}s, or is
     * null if their total wouldn't fit in one.
     */
    record IndexedBallots(int[][] positions, BigInteger[] counts, long[] smallCounts) {

        static IndexedBallots of(List<Candidate> candidates, List<Ballot> ballots) {
            Map<Candidate, Integer> indexOf = new HashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                indexOf.put(candidates.get(i), i);
            }
            Map<PositionsKey, Integer> seen = new LinkedHashMap<>();
            List<int[]> positions = new ArrayList<>();
            List<BigInteger> counts = new ArrayList<>();
            for (Ballot ballot : ballots) {
                int[] position = new int[candidates.size()];
                Arrays.fill(position, Integer.MAX_VALUE);
                for (int rank = 0; rank < ballot.ranking().size(); rank++) {
                    for (Candidate candidate : ballot.ranking().get(rank)) {
                        Integer index = indexOf.get(candidate);
                        if (index != null) { // a withdrawn candidate
                            position[index] = rank;
                        }
                    }
                }
                Integer at = seen.putIfAbsent(new PositionsKey(position), positions.size());
                if (at == null) {
                    positions.add(position);
                    counts.add(ballot.count());
                } else {
                    counts.set(at, counts.get(at).add(ballot.count()));
                }
            }
            BigInteger total = counts.stream().reduce(BigInteger.ZERO, BigInteger::add);
            long[] smallCounts = total.bitLength() < Long.SIZE
                    ? counts.stream().mapToLong(BigInteger::longValueExact).toArray() : null;
            return new IndexedBallots(positions.toArray(int[][]::new), counts.toArray(BigInteger[]::new), smallCounts);
        }
    }

    private record PositionsKey(int[] positions) {
        @Override
        public boolean equals(Object other) {
            return other instanceof PositionsKey(int[] otherPositions) && Arrays.equals(positions, otherPositions);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(positions);
        }
    }

    /**
     * N[contest without member k; member k], for every k. Only the order a ballot gives the
     * contest's own candidates matters, so each ballot is first reduced to that: each member's
     * level, the number of members it ranks ahead of them (unranked members share the level
     * below every ranked one). Identical reductions are merged, which leaves far fewer to go
     * through for each member.
     */
    private static Fraction[] contestSupports(int[] contest, IndexedBallots ballots, int seats) {
        int size = seats + 1;
        Reduced reduced = Reduced.of(contest, ballots);
        Fraction[] supports = new Fraction[size];
        for (int k = 0; k < size; k++) {
            supports[k] = support(patterns(reduced, k, size), seats);
        }
        return supports;
    }

    /**
     * N[the contest's other members; member k]: by the closed form while the committee has at
     * most {@link #CLOSED_FORM_SEATS} members, else by {@link LargeCommitteeSupport}. Both give
     * the same exact figure; the closed form's 2^M subsets are quicker for small committees.
     */
    static Fraction support(Reduced reduced, int k) {
        if (reduced.levels().length == 0) {
            return Fraction.ZERO; // no ballots at all, so no support
        }
        int size = reduced.levels()[0].length;
        return size - 1 <= CLOSED_FORM_SEATS
                ? closedFormSupport(reduced, k)
                : LargeCommitteeSupport.of(reduced.levels(), reduced.counts(), k);
    }

    /** The closed form for any committee small enough to try its 2^M subsets. */
    static Fraction closedFormSupport(Reduced reduced, int k) {
        int size = reduced.levels()[0].length;
        return support(patterns(reduced, k, size), size - 1);
    }

    /** See {@link #support(Reduced, int)}. */
    static final int CLOSED_FORM_SEATS = 20;

    /** Ballots reduced to one contest: {@code levels[b][j]} is member j's level on group b. */
    record Reduced(int[][] levels, BigInteger[] counts) {

        static Reduced of(int[] contest, IndexedBallots ballots) {
            int size = contest.length;
            int bits = Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(size - 1));
            boolean packable = bits * size <= Long.SIZE && ballots.smallCounts() != null;
            int n = ballots.positions().length;
            LongIndex index = packable ? new LongIndex(n) : null;
            int[][] levels = new int[n][];
            long[] smallCounts = packable ? new long[n] : null;
            BigInteger[] counts = packable ? null : new BigInteger[n];
            int groups = 0;
            int[] level = new int[size];
            int[] at = new int[size];
            for (int b = 0; b < n; b++) {
                int[] position = ballots.positions()[b];
                for (int j = 0; j < size; j++) {
                    at[j] = position[contest[j]];
                }
                long key = 0;
                for (int j = 0; j < size; j++) {
                    int ahead = 0;
                    for (int i = 0; i < size; i++) {
                        if (at[i] < at[j]) {
                            ahead++;
                        }
                    }
                    level[j] = ahead;
                    key |= (long) ahead << (bits * j);
                }
                if (packable) {
                    int g = index.of(key);
                    if (g == groups) {
                        levels[groups++] = level.clone();
                    }
                    smallCounts[g] += ballots.smallCounts()[b];
                } else {
                    levels[groups] = level.clone();
                    counts[groups++] = ballots.counts()[b];
                }
            }
            if (packable) {
                counts = new BigInteger[groups];
                for (int g = 0; g < groups; g++) {
                    counts[g] = BigInteger.valueOf(smallCounts[g]);
                }
            }
            return new Reduced(Arrays.copyOf(levels, groups), counts);
        }
    }

    /**
     * Each voter's pattern against member {@code k} of the contest, merged: bit j of the low
     * {@code size - 1} bits says they prefer the j-th other member to it, bit j of the high bits
     * that they rank the two level.
     */
    private static Map<Long, BigInteger> patterns(Reduced reduced, int k, int size) {
        Map<Long, BigInteger> patterns = new HashMap<>();
        int seats = size - 1;
        for (int b = 0; b < reduced.levels().length; b++) {
            int[] level = reduced.levels()[b];
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
            patterns.merge(prefer | tie << seats, reduced.counts()[b], BigInteger::add);
        }
        return patterns;
    }

    /**
     * Completes {@code patterns} (section 8.1.1) and takes the closed-form support: the minimum
     * over non-empty J of supply(J) / |J|, where supply(J) is the weight preferring some
     * member of J, i.e. the total less the weight preferring only members outside J.
     *
     * <p>Exact fractions over all 2^seats subsets are slow, since the completed weights' common
     * denominator multiplies many voter counts together. So the subsets are first compared in
     * {@code double}, and only those within {@link #CANDIDATE_TOLERANCE} of the smallest are
     * then computed exactly. The tolerance is far wider than double rounding error over these
     * sums, so the true minimum is always among them, and the result is exact.
     */
    private static Fraction support(Map<Long, BigInteger> patterns, int seats) {
        Completion completion = new Completion(patterns, seats);
        int size = 1 << seats;
        int full = size - 1;

        double[] within = completion.approximateWeights();
        for (int bit = 0; bit < seats; bit++) {
            for (int set = 0; set < size; set++) {
                if ((set & (1 << bit)) != 0) {
                    within[set] += within[set ^ (1 << bit)];
                }
            }
        }
        double total = within[full];
        double smallest = Double.POSITIVE_INFINITY;
        double[] value = new double[size];
        for (int j = 1; j < size; j++) {
            value[j] = (total - within[full & ~j]) / Integer.bitCount(j);
            smallest = Math.min(smallest, value[j]);
        }

        Fraction exactTotal = Fraction.of(completion.total());
        Fraction best = null;
        for (int j = 1; j < size; j++) {
            if (value[j] <= smallest + CANDIDATE_TOLERANCE * total) {
                Fraction exact = exactTotal.subtract(completion.exactWithin(full & ~j))
                        .divide(Fraction.of(Integer.bitCount(j)));
                if (best == null || exact.compareTo(best) < 0) {
                    best = exact;
                }
            }
        }
        return best;
    }

    /** Relative to the total weight; see {@link #support}. */
    private static final double CANDIDATE_TOLERANCE = 1e-7;

    /**
     * Proportional completion of one link's voters. A voter ranking the members in tie mask T
     * level with g is replaced by the original voters who aren't level with g on all of T
     * (the donors), in proportion, each passing on what they say about T. A donor itself level
     * with g on part of T, T', is completed the same way for T', again from the original
     * voters. With no donors, every order of T and g is equally likely: an order with s of T's
     * k members above g is one of s! (k - s)! out of (k + 1)!.
     */
    static final class Completion {
        private final int seats;
        private final long[] prefers;
        private final long[] ties;
        private final BigInteger[] counts;
        private final double[] approximateCounts;
        private final BigInteger total;
        private final LongIndex index;
        private final Map<Long, Shares> approximate = new HashMap<>();
        private final Map<Long, ExactDonors> exactDonors = new HashMap<>();
        private final Map<Long, Map<Long, Fraction>> exact = new HashMap<>();

        /**
         * The donors for one tie mask, grouped by what they say about it: group g places
         * {@code fixed[g]} above g and is still level with g on {@code stillTied[g]};
         * {@code groupOf[i]} is original pattern i's group, or -1 if it isn't a donor.
         */
        private record Donors(int[] groupOf, long[] fixed, long[] stillTied) {
        }

        private record ExactDonors(Donors donors, BigInteger[] counts, BigInteger total) {
        }

        /** From voters' patterns as separate masks, for committees of up to 63 members. */
        Completion(long[] prefers, long[] ties, BigInteger[] counts, int seats) {
            this.seats = seats;
            this.prefers = prefers;
            this.ties = ties;
            this.counts = counts;
            approximateCounts = new double[counts.length];
            BigInteger sum = BigInteger.ZERO;
            for (int i = 0; i < counts.length; i++) {
                approximateCounts[i] = counts[i].doubleValue();
                sum = sum.add(counts[i]);
            }
            total = sum;
            index = new LongIndex(counts.length);
        }

        Completion(Map<Long, BigInteger> patterns, int seats) {
            this.seats = seats;
            long low = (1L << seats) - 1;
            int n = patterns.size();
            prefers = new long[n];
            ties = new long[n];
            counts = new BigInteger[n];
            approximateCounts = new double[n];
            BigInteger sum = BigInteger.ZERO;
            int i = 0;
            for (Map.Entry<Long, BigInteger> pattern : patterns.entrySet()) {
                prefers[i] = pattern.getKey() & low;
                ties[i] = pattern.getKey() >>> seats;
                counts[i] = pattern.getValue();
                approximateCounts[i] = pattern.getValue().doubleValue();
                sum = sum.add(pattern.getValue());
                i++;
            }
            total = sum;
            index = new LongIndex(n);
        }

        BigInteger total() {
            return total;
        }

        private Donors donors(long tie) {
            if (2 * seats > Long.SIZE) {
                return wideDonors(tie);
            }
            index.clear();
            int[] groupOf = new int[counts.length];
            long[] fixed = new long[counts.length];
            long[] stillTied = new long[counts.length];
            for (int i = 0; i < counts.length; i++) {
                long theirTie = ties[i] & tie;
                if (theirTie == tie) {
                    groupOf[i] = -1;
                    continue;
                }
                int groups = index.size();
                int g = index.of((prefers[i] & tie) | theirTie << seats);
                if (g == groups) {
                    fixed[g] = prefers[i] & tie;
                    stillTied[g] = theirTie;
                }
                groupOf[i] = g;
            }
            return new Donors(groupOf, Arrays.copyOf(fixed, index.size()), Arrays.copyOf(stillTied, index.size()));
        }

        /** {@link #donors}, for committees too large to pack a donor's two masks into one key. */
        private Donors wideDonors(long tie) {
            Map<List<Long>, Integer> groups = new HashMap<>();
            int[] groupOf = new int[counts.length];
            long[] fixed = new long[counts.length];
            long[] stillTied = new long[counts.length];
            for (int i = 0; i < counts.length; i++) {
                long theirTie = ties[i] & tie;
                if (theirTie == tie) {
                    groupOf[i] = -1;
                    continue;
                }
                int size = groups.size();
                int g = groups.computeIfAbsent(List.of(prefers[i] & tie, theirTie), _ -> size);
                if (g == size) {
                    fixed[g] = prefers[i] & tie;
                    stillTied[g] = theirTie;
                }
                groupOf[i] = g;
            }
            return new Donors(groupOf, Arrays.copyOf(fixed, groups.size()), Arrays.copyOf(stillTied, groups.size()));
        }

        /** Every completed pattern's weight, approximately, indexed by its prefer mask. */
        double[] approximateWeights() {
            double[] weights = new double[1 << seats];
            for (int i = 0; i < counts.length; i++) {
                if (ties[i] == 0) {
                    weights[(int) prefers[i]] += approximateCounts[i];
                } else {
                    Shares shares = approximate(ties[i]);
                    for (int a = 0; a < shares.size(); a++) {
                        weights[(int) (prefers[i] | shares.key(a))] += approximateCounts[i] * shares.value(a);
                    }
                }
            }
            return weights;
        }

        /**
         * How a voter level with g on {@code tie} is completed, approximately: each set of
         * members of {@code tie} it places above g, with its share. Kept sparse, since a tie's
         * completion seldom reaches more than a few of its 2^|tie| subsets.
         */
        private Shares approximate(long tie) {
            Shares known = approximate.get(tie);
            if (known != null) {
                return known;
            }
            LongDoubleMap shares = new LongDoubleMap();
            Donors donors = donors(tie);
            double[] weights = new double[donors.fixed().length];
            double sum = 0;
            for (int i = 0; i < counts.length; i++) {
                if (donors.groupOf()[i] >= 0) {
                    weights[donors.groupOf()[i]] += approximateCounts[i];
                    sum += approximateCounts[i];
                }
            }
            if (weights.length > 0) {
                for (int d = 0; d < weights.length; d++) {
                    double share = weights[d] / sum;
                    long stillTied = donors.stillTied()[d];
                    if (stillTied == 0) {
                        shares.add(donors.fixed()[d], share);
                    } else {
                        Shares inner = approximate(stillTied);
                        for (int a = 0; a < inner.size(); a++) {
                            shares.add(donors.fixed()[d] | inner.key(a), share * inner.value(a));
                        }
                    }
                }
            } else {
                int k = Long.bitCount(tie);
                double orders = factorial(k + 1).doubleValue();
                for (long a = 0; a < 1L << k; a++) {
                    int s = Long.bitCount(a);
                    shares.add(Long.expand(a, tie), factorial(s).multiply(factorial(k - s)).doubleValue() / orders);
                }
            }
            Shares compact = shares.compact(tie);
            approximate.put(tie, compact);
            return compact;
        }

        private ExactDonors exactDonors(long tie) {
            ExactDonors known = exactDonors.get(tie);
            if (known != null) {
                return known;
            }
            Donors donors = donors(tie);
            BigInteger[] weights = new BigInteger[donors.fixed().length];
            Arrays.fill(weights, BigInteger.ZERO);
            BigInteger sum = BigInteger.ZERO;
            for (int i = 0; i < counts.length; i++) {
                if (donors.groupOf()[i] >= 0) {
                    weights[donors.groupOf()[i]] = weights[donors.groupOf()[i]].add(counts[i]);
                    sum = sum.add(counts[i]);
                }
            }
            ExactDonors result = new ExactDonors(donors, weights, sum);
            exactDonors.put(tie, result);
            return result;
        }

        /** The weight preferring no member outside {@code inside}, exactly. */
        Fraction exactWithin(long inside) {
            Fraction sum = Fraction.ZERO;
            for (int i = 0; i < counts.length; i++) {
                if ((prefers[i] & ~inside) == 0) {
                    Fraction count = Fraction.of(counts[i]);
                    sum = sum.add(ties[i] == 0 ? count : count.multiply(exactInside(ties[i], inside & ties[i])));
                }
            }
            return sum;
        }

        /** The share of a voter level with g on {@code tie} that completes with only {@code inside} above g. */
        private Fraction exactInside(long tie, long inside) {
            Map<Long, Fraction> byInside = exact.computeIfAbsent(tie, _ -> new HashMap<>());
            Fraction known = byInside.get(inside);
            if (known != null) {
                return known;
            }
            Fraction share = Fraction.ZERO;
            ExactDonors donors = exactDonors(tie);
            if (donors.total().signum() > 0) {
                for (int d = 0; d < donors.counts().length; d++) {
                    if ((donors.donors().fixed()[d] & ~inside) != 0) {
                        continue;
                    }
                    long stillTied = donors.donors().stillTied()[d];
                    Fraction weight = Fraction.of(donors.counts()[d]);
                    share = share.add(stillTied == 0 ? weight
                            : weight.multiply(exactInside(stillTied, inside & stillTied)));
                }
                share = share.divide(Fraction.of(donors.total()));
            } else {
                // Orders placing above g exactly s members, all from `inside`: C(|inside|, s) of
                // the subsets, each s! (k - s)! / (k + 1)!.
                int k = Long.bitCount(tie);
                int room = Long.bitCount(inside);
                BigInteger orders = BigInteger.ZERO;
                for (int s = 0; s <= room; s++) {
                    orders = orders.add(Binomials.exact(room, s).multiply(factorial(s)).multiply(factorial(k - s)));
                }
                share = Fraction.of(orders, factorial(k + 1));
            }
            byInside.put(inside, share);
            return share;
        }
    }

    /**
     * Numbers distinct {@code long} keys 0, 1, 2... in order of first appearance: a primitive
     * open-addressing table, since boxing every key in a {@code HashMap} dominated the count.
     */
    private static final class LongIndex {
        private final long[] keys;
        private final int[] slots;
        private final long[] used;
        private final int shift;
        private int size;

        LongIndex(int capacity) {
            int length = Integer.highestOneBit(Math.max(2, capacity) * 2 - 1) * 2;
            keys = new long[length];
            slots = new int[length];
            shift = Long.SIZE - Integer.numberOfTrailingZeros(length);
            used = new long[Math.max(2, capacity)];
            Arrays.fill(slots, -1);
        }

        /** Forgets every key, in time proportional to how many there were. */
        void clear() {
            int mask = keys.length - 1;
            for (int i = 0; i < size; i++) {
                int at = slot(used[i]);
                while (slots[at] >= 0) {
                    slots[at] = -1;
                    at = (at + 1) & mask;
                }
            }
            size = 0;
        }

        /** Fibonacci hashing: the product's top bits depend on every bit of the key. */
        private int slot(long key) {
            return (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
        }

        int of(long key) {
            int mask = keys.length - 1;
            int at = slot(key);
            while (slots[at] >= 0) {
                if (keys[at] == key) {
                    return slots[at];
                }
                at = (at + 1) & mask;
            }
            keys[at] = key;
            slots[at] = size;
            used[size] = key;
            return size++;
        }

        int size() {
            return size;
        }
    }

    private static BigInteger factorial(int n) {
        BigInteger result = BigInteger.ONE;
        for (int i = 2; i <= n; i++) {
            result = result.multiply(BigInteger.valueOf(i));
        }
        return result;
    }

    // ---- committees and paths ----------------------------------------------------------

    /**
     * The committee graph: every committee's links to the committees one swap away, with the
     * margin of each. Every link has a reverse with the opposite margin, both decided within one
     * contest, so each contest's margins are made once and a link and its reverse share one: a
     * target stored as {@code ~y} means the link's margin is the negative of the one held.
     */
    private record Graph(int[][] targets, Fraction[][] margins) {

        static Graph of(List<int[]> committees, Fraction[][] supports, int n, int seats, Binomials binomials) {
            int[][] targets = new int[committees.size()][];
            Fraction[][] margins = new Fraction[committees.size()][];
            // pairMargins[s][pair(i, j)], i < j: N[contest s without i; i] - N[contest s without j; j].
            Fraction[][] pairMargins = Arrays.stream(supports).parallel().map(support -> {
                Fraction[] pairs = new Fraction[support.length * (support.length - 1) / 2];
                for (int i = 0; i < support.length; i++) {
                    for (int j = i + 1; j < support.length; j++) {
                        pairs[pair(i, j, support.length)] = support[i].subtract(support[j]);
                    }
                }
                return pairs;
            }).toArray(Fraction[][]::new);
            IntStream.range(0, committees.size()).parallel().forEach(x -> {
                int[] committee = committees.get(x);
                boolean[] in = new boolean[n];
                for (int member : committee) {
                    in[member] = true;
                }
                int links = seats * (n - seats);
                int[] to = new int[links];
                Fraction[] margin = new Fraction[links];
                int[] contest = new int[seats + 1];
                int[] next = new int[seats];
                int l = 0;
                for (int c = 0; c < n; c++) {
                    if (in[c]) {
                        continue;
                    }
                    insert(committee, c, contest);
                    int s = binomials.rank(contest);
                    int cAt = Arrays.binarySearch(contest, c);
                    for (int i = 0; i < seats; i++) {
                        int b = committee[i];
                        int bAt = Arrays.binarySearch(contest, b);
                        // X = committee, Y = X - b + c: strength (N[X; c], N[Y; b]).
                        remove(contest, bAt, next);
                        int y = binomials.rank(next);
                        if (cAt < bAt) {
                            to[l] = y;
                            margin[l] = pairMargins[s][pair(cAt, bAt, seats + 1)];
                        } else {
                            to[l] = ~y;
                            margin[l] = pairMargins[s][pair(bAt, cAt, seats + 1)];
                        }
                        l++;
                    }
                }
                targets[x] = to;
                margins[x] = margin;
            });
            return new Graph(targets, margins);
        }

        /** The index of positions {@code i < j} among a contest of {@code size}'s pairs. */
        private static int pair(int i, int j, int size) {
            return i * (2 * size - i - 1) / 2 + j - i - 1;
        }

        /** The committees no other committee beats, found from {@code start} (see the class javadoc). */
        List<Integer> unbeaten(int start) {
            int current = start;
            Fraction[] from;
            Fraction[] to;
            while (true) {
                from = widest(current, false);
                to = widest(current, true);
                int beater = beaterOf(current, from, to);
                if (beater < 0) {
                    break;
                }
                current = beater;
            }
            List<Integer> unbeaten = new ArrayList<>();
            for (int x = 0; x < targets.length; x++) {
                if (x == current || (from[x].compareTo(to[x]) == 0
                        && beaterOf(x, widest(x, false), widest(x, true)) < 0)) {
                    unbeaten.add(x);
                }
            }
            return unbeaten;
        }

        /**
         * A committee that beats {@code x}, given x's strongest paths to and from every other
         * committee, or -1. Of several, the one with the strongest path to x.
         */
        private static int beaterOf(int x, Fraction[] from, Fraction[] to) {
            int beater = -1;
            for (int y = 0; y < from.length; y++) {
                if (y != x && to[y].compareTo(from[y]) > 0 && (beater < 0 || to[y].compareTo(to[beater]) > 0)) {
                    beater = y;
                }
            }
            return beater;
        }

        /**
         * The strongest path from {@code source} to every committee, or with {@code reverse}
         * from every committee to it: widest-path Dijkstra. Every committee is reachable, since
         * links come in pairs and any committee becomes any other by swaps.
         */
        private Fraction[] widest(int source, boolean reverse) {
            Fraction[] best = new Fraction[targets.length];
            boolean[] done = new boolean[targets.length];
            done[source] = true;
            PriorityQueue<Reach> queue = new PriorityQueue<>();
            relax(source, null, reverse, best, done, queue);
            while (!queue.isEmpty()) {
                Reach reach = queue.poll();
                if (done[reach.committee()]) {
                    continue;
                }
                done[reach.committee()] = true;
                relax(reach.committee(), reach.strength(), reverse, best, done, queue);
            }
            return best;
        }

        private void relax(int x, Fraction strength, boolean reverse, Fraction[] best, boolean[] done,
                PriorityQueue<Reach> queue) {
            for (int l = 0; l < targets[x].length; l++) {
                int target = targets[x][l];
                int y = target < 0 ? ~target : target;
                if (done[y]) {
                    continue;
                }
                // Reversed, the link used is y -> x, whose margin is the opposite of x -> y's.
                Fraction margin = (target < 0) != reverse ? margins[x][l].negate() : margins[x][l];
                Fraction through = strength == null || margin.compareTo(strength) < 0 ? margin : strength;
                if (best[y] == null || through.compareTo(best[y]) > 0) {
                    best[y] = through;
                    queue.add(new Reach(y, through));
                }
            }
        }
    }

    /** Strongest first. */
    private record Reach(int committee, Fraction strength) implements Comparable<Reach> {
        @Override
        public int compareTo(Reach other) {
            return other.strength.compareTo(strength);
        }
    }

    private static void insert(int[] committee, int c, int[] into) {
        int o = 0;
        boolean placed = false;
        for (int member : committee) {
            if (!placed && c < member) {
                into[o++] = c;
                placed = true;
            }
            into[o++] = member;
        }
        if (!placed) {
            into[o] = c;
        }
    }

    private static void remove(int[] contest, int at, int[] into) {
        for (int i = 0, o = 0; i < contest.length; i++) {
            if (i != at) {
                into[o++] = contest[i];
            }
        }
    }

    /** All {@code size}-subsets of {@code 0..n-1}, each sorted, at the index {@link Binomials#rank} gives it. */
    private static List<int[]> combinations(int n, int size, Binomials binomials) {
        int[][] all = new int[Binomials.exact(n, size).intValueExact()][];
        int[] current = new int[size];
        for (int i = 0; i < size; i++) {
            current[i] = i;
        }
        while (true) {
            all[binomials.rank(current)] = current.clone();
            int i = size - 1;
            while (i >= 0 && current[i] == n - size + i) {
                i--;
            }
            if (i < 0) {
                return Arrays.asList(all);
            }
            current[i]++;
            for (int j = i + 1; j < size; j++) {
                current[j] = current[j - 1] + 1;
            }
        }
    }

    /** Binomial coefficients, for ranking sorted subsets in the combinatorial number system. */
    private static final class Binomials {
        private final int[][] table;

        Binomials(int n, int maxK) {
            table = new int[n + 1][maxK + 1];
            for (int i = 0; i <= n; i++) {
                table[i][0] = 1;
                for (int k = 1; k <= Math.min(i, maxK); k++) {
                    // Only ever read for subsets that fit the committee cap's range; saturate above it.
                    long value = (long) table[i - 1][k - 1] + (k <= i - 1 ? table[i - 1][k] : 0);
                    table[i][k] = (int) Math.min(value, Integer.MAX_VALUE);
                }
            }
        }

        /** Sum of C(subset[i], i + 1): a distinct index in 0..C(n, size) - 1 for each sorted subset. */
        int rank(int[] subset) {
            int rank = 0;
            for (int i = 0; i < subset.length; i++) {
                rank += table[subset[i]][i + 1];
            }
            return rank;
        }

        static BigInteger exact(int n, int k) {
            BigInteger result = BigInteger.ONE;
            for (int i = 0; i < Math.min(k, n - k); i++) {
                result = result.multiply(BigInteger.valueOf(n - i)).divide(BigInteger.valueOf(i + 1));
            }
            return result;
        }
    }
}
