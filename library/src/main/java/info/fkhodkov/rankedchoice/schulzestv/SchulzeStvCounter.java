package info.fkhodkov.rankedchoice.schulzestv;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.core.util.Interruption;
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
 * committee, a link and its reverse sharing one margin, held as its rank: 18 candidates for 7
 * seats, about the most links the cap allows (2.45 million), count in 128 MB of heap. {@link
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
        Fraction[][] supports;
        Interruption.Watch watch = Interruption.watch();
        if (ContestReductions.applies(seats + 1, indexed)) {
            Fraction[][] built = new Fraction[contests.size()][];
            ContestReductions.forEach(n, seats + 1, indexed, watch, (contest, reduced) -> {
                watch.check();
                built[binomials.rank(contest)] = contestSupports(reduced, seats);
            });
            supports = built;
        } else {
            supports = watch.call(() -> contests.parallelStream()
                    .map(contest -> {
                        try (Interruption.Piece _ = watch.enter()) {
                            return contestSupports(Reduced.of(contest, indexed), seats);
                        }
                    })
                    .toArray(Fraction[][]::new));
        }

        Graph graph = Graph.of(committees, supports, n, seats, binomials, watch);
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
     * through for each member. Contests small enough are reduced together ({@link
     * ContestReductions}), others one by one ({@link Reduced#of}).
     */
    private static Fraction[] contestSupports(Reduced reduced, int seats) {
        int size = seats + 1;
        Completion[] completions = completions(reduced, size);
        Fraction[] supports = new Fraction[size];
        for (int k = 0; k < size; k++) {
            supports[k] = support(completions[k]);
        }
        return supports;
    }

    /**
     * {@link #patterns} for every member at once: each group's members are sorted into their
     * levels once, and each member's pattern read from them, rather than every member compared
     * with every other for each.
     */
    static Completion[] completions(Reduced reduced, int size) {
        int seats = size - 1;
        int[][] levels = reduced.levels();
        BigInteger[] counts = reduced.counts();
        int n = levels.length;
        BigInteger total = BigInteger.ZERO;
        for (BigInteger count : counts) {
            total = total.add(count);
        }
        boolean small = total.bitLength() < Long.SIZE;
        LongIndex[] index = new LongIndex[size];
        long[][] keys = new long[size][n];
        long[][] sums = small ? new long[size][n] : null;
        BigInteger[][] bigSums = small ? null : new BigInteger[size][n];
        int[] patterns = new int[size];
        for (int k = 0; k < size; k++) {
            index[k] = new LongIndex(n);
        }
        long[] atLevel = new long[size];
        long[] under = new long[size];
        for (int b = 0; b < n; b++) {
            int[] level = levels[b];
            Arrays.fill(atLevel, 0);
            for (int j = 0; j < size; j++) {
                atLevel[level[j]] |= 1L << j;
            }
            for (int l = 1; l < size; l++) {
                under[l] = under[l - 1] | atLevel[l - 1];
            }
            long count = small ? counts[b].longValue() : 0;
            for (int k = 0; k < size; k++) {
                // Against member k: the others below its level preferred, those on it level.
                long prefer = without(under[level[k]], k);
                long tie = without(atLevel[level[k]], k);
                long key = prefer | tie << seats;
                int at = index[k].of(key);
                if (at == patterns[k]) {
                    keys[k][patterns[k]++] = key;
                }
                if (small) {
                    sums[k][at] += count;
                } else {
                    bigSums[k][at] = bigSums[k][at] == null ? counts[b] : bigSums[k][at].add(counts[b]);
                }
            }
        }
        long low = (1L << seats) - 1;
        Completion[] completions = new Completion[size];
        for (int k = 0; k < size; k++) {
            int m = patterns[k];
            long[] prefers = new long[m];
            long[] ties = new long[m];
            BigInteger[] weights = new BigInteger[m];
            for (int i = 0; i < m; i++) {
                prefers[i] = keys[k][i] & low;
                ties[i] = keys[k][i] >>> seats;
                weights[i] = small ? BigInteger.valueOf(sums[k][i]) : bigSums[k][i];
            }
            completions[k] = new Completion(prefers, ties, weights, seats);
        }
        return completions;
    }

    /** {@code mask} with bit k taken out, the bits above it moving down one. */
    private static long without(long mask, int k) {
        return mask & ((1L << k) - 1) | (mask >>> (k + 1)) << k;
    }

    /**
     * N[the contest's other members; member k]: by the closed form while the committee has at
     * most {@link #CLOSED_FORM_SEATS} members, else by {@link MinimumNormSupport}, which needs
     * no table over the 2^M subsets. Both give the same exact figure.
     */
    static Fraction support(Reduced reduced, int k) {
        if (reduced.levels().length == 0) {
            return Fraction.ZERO; // no ballots at all, so no support
        }
        int seats = reduced.levels()[0].length - 1;
        return seats <= CLOSED_FORM_SEATS ? closedFormSupport(reduced, k)
                : MinimumNormSupport.of(reduced.levels(), reduced.counts(), k);
    }

    /** The closed form for any committee small enough to try its 2^M subsets. */
    static Fraction closedFormSupport(Reduced reduced, int k) {
        int size = reduced.levels()[0].length;
        return support(new Completion(patterns(reduced, k, size), size - 1));
    }

    /**
     * See {@link #support(Reduced, int)}. The closed form's table holds 2^M sets, and each voter
     * level with the outsider costs a pass over the voters for every subset of their tie, so on
     * short ballots large committees grow slow: the proportional ranking of 24 candidates by
     * 10,000 voters ranking about 5 took 115 s with the closed form up to 20 members, 5 s up to
     * 8. Up to 8, the table is small and the closed form exact without proviso.
     */
    static final int CLOSED_FORM_SEATS = 8;

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
    static Map<Long, BigInteger> patterns(Reduced reduced, int k, int size) {
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
     * The closed-form support of a link's completed voters (section 8.1.1): the minimum
     * over non-empty J of supply(J) / |J|, where supply(J) is the weight preferring some
     * member of J, i.e. the total less the weight preferring only members outside J.
     *
     * <p>Exact fractions over all 2^seats subsets are slow, since the completed weights' common
     * denominator multiplies many voter counts together. So the subsets are first compared in
     * {@code double}, and only those within {@link #CANDIDATE_TOLERANCE} of the smallest are
     * then computed exactly. The tolerance is far wider than double rounding error over these
     * sums, so the true minimum is always among them, and the result is exact.
     */
    private static Fraction support(Completion completion) {
        int seats = completion.seats();
        int size = 1 << seats;
        int full = size - 1;

        double[] within = completion.approximateWithin();
        double total = within[full];
        double smallest = Double.POSITIVE_INFINITY;
        for (int j = 1; j < size; j++) {
            smallest = Math.min(smallest, (total - within[full & ~j]) / Integer.bitCount(j));
        }

        Fraction exactTotal = Fraction.of(completion.total());
        Fraction best = null;
        for (int j = 1; j < size; j++) {
            if ((total - within[full & ~j]) / Integer.bitCount(j) <= smallest + CANDIDATE_TOLERANCE * total) {
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
     * voters. With no donors, every order of T and g is equally likely.
     *
     * <p>That is a chain of donors drawn from all the original voters, a voter who's level with g
     * on all of what's left changing nothing, until nothing is left. So whether the members of a
     * set R all end up below g depends only on the voters who say something about R, not on the
     * rest of the tie: the voter who decides the first of R must place none of R above g, and R's
     * members still level with g on that voter's ballot must then end up below in turn. That
     * chance, below(R), is the sum over the voters who aren't level with g on all of R and place
     * none of it above g, of their weight times below(R's members they leave level with g), over
     * the weight of all who aren't level on all of R; with nobody, every order of R and g is
     * equally likely, and g comes first in 1 of |R| + 1. A voter level with g on T, preferring
     * the members P, then prefers no member outside S once completed with the chance [P within
     * S] below(T less S). It depends on R alone, so it's worked out once for each R, rather than
     * listing out each tie's completion over its subsets, which can run to millions.
     */
    static final class Completion {
        private final int seats;
        private final long[] prefers;
        private final long[] ties;
        private final BigInteger[] counts;
        private final double[] approximateCounts;
        private final BigInteger total;
        private final Map<Long, Fraction> exactBelow = new HashMap<>();
        private final Map<Long, Double> approximateBelow = new HashMap<>();

        /**
         * From ballots reduced to a contest of up to 64 members ({@code levels[b][j]} is member
         * j's level on group b, {@code counts[b]} its voters), against member {@code k}.
         */
        static Completion against(int[][] levels, BigInteger[] counts, int k) {
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
            return new Completion(prefers, ties, weights, seats);
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
        }

        BigInteger total() {
            return total;
        }

        int seats() {
            return seats;
        }

        /** Each pattern's members preferred to g, as a mask. */
        long[] prefers() {
            return prefers;
        }

        /** Each pattern's members level with g, as a mask. */
        long[] ties() {
            return ties;
        }

        /** Each pattern's voters. */
        BigInteger[] counts() {
            return counts;
        }

        /**
         * For every set S of members, the completed weight preferring no member outside S,
         * approximately. Each voter level with g costs a pass over the sets holding what they
         * prefer, and each below(R) one over all the voters; where those come to more than the
         * 3^seats of {@link #approximateWithinByPlaces}, that's used instead.
         */
        double[] approximateWithin() {
            int tied = 0;
            for (long tie : ties) {
                if (tie != 0) {
                    tied++;
                }
            }
            boolean exactInDoubles = total.bitLength() <= 53;
            if (seats <= PLACES_SEATS && exactInDoubles && (long) tied << seats >= powerOfThree(seats)) {
                return approximateWithinByPlaces();
            }
            return approximateWithinByVoters();
        }

        /**
         * Up to 12 members besides g, 3^seats doubles take about 4 MB. On 10,000 short ballots of
         * 24 candidates, the two ways took about as long for 14 seats; for 12, this one a third of
         * the time, for 10 and for 8 (on 16 candidates) a quarter or a fifth.
         */
        private static final int PLACES_SEATS = 12;

        private static int powerOfThree(int n) {
            int power = 1;
            for (int i = 0; i < n; i++) {
                power *= 3;
            }
            return power;
        }

        /**
         * {@link #approximateWithin()} from each voter's place for each member, level with g,
         * below g or (preferring it) above. For a set R and a part Q of it, w(R, Q) is the weight
         * of the voters placing none of R above g, Q level and the rest of R below; a transform
         * over the 3^seats places, each member above g taken as anywhere, gives every w(R, Q).
         * The voters deciding R are those not level on all of it, all but w(R, R), so below(R)
         * is the sum over Q short of R of w(R, Q) below(Q), over them. And the weight preferring
         * no member outside S, with R the members outside it, is the same sum over every Q,
         * R itself included: those voters place none of R above g, and what they leave level
         * of it, Q, must end up below.
         *
         * <p>The weights are sums of whole voters, exact in doubles while the total is under
         * 2^53, so the weight not deciding is zero exactly when it should be.
         */
        double[] approximateWithinByPlaces() {
            int size = 1 << seats;
            int full = size - 1;
            // spread[m]: the place index with 1 at each member of m, 0 elsewhere.
            int[] spread = new int[size];
            for (int m = 1; m < size; m++) {
                spread[m] = spread[m & (m - 1)] + powerOfThree(Integer.numberOfTrailingZeros(m));
            }
            // Places: 0 above g (and, once transformed, anywhere), 1 level with g, 2 below g.
            double[] weights = new double[powerOfThree(seats)];
            double sum = 0;
            for (int d = 0; d < counts.length; d++) {
                int below = full & ~(int) (prefers[d] | ties[d]);
                weights[spread[(int) ties[d]] + 2 * spread[below]] += approximateCounts[d];
                sum += approximateCounts[d];
            }
            for (int place = 1; place < weights.length; place *= 3) {
                for (int high = 0; high < weights.length; high += 3 * place) {
                    for (int i = high; i < high + place; i++) {
                        weights[i] += weights[i + place] + weights[i + 2 * place];
                    }
                }
            }
            double[] below = new double[size];
            double[] within = new double[size];
            below[0] = 1;
            within[full] = sum;
            for (int r = 1; r < size; r++) {
                // w(R, Q) is weights[spread[Q] + 2 * spread[R less Q]].
                int allBelow = 2 * spread[r];
                double kept = 0;
                for (int q = (r - 1) & r; ; q = (q - 1) & r) {
                    kept += below[q] * weights[allBelow - spread[q]];
                    if (q == 0) {
                        break;
                    }
                }
                double level = weights[spread[r]];
                double deciding = sum - level;
                below[r] = deciding > 0 ? kept / deciding : 1.0 / (Integer.bitCount(r) + 1);
                within[full & ~r] = kept + level * below[r];
            }
            return within;
        }

        /**
         * {@link #approximateWithin()} voter by voter: those level with g on nothing by a zeta
         * transform of their prefer masks, each other voter over the sets holding what they prefer.
         */
        double[] approximateWithinByVoters() {
            int size = 1 << seats;
            double[] within = new double[size];
            for (int i = 0; i < counts.length; i++) {
                if (ties[i] == 0) {
                    within[(int) prefers[i]] += approximateCounts[i];
                }
            }
            for (int bit = 0; bit < seats; bit++) {
                for (int set = 0; set < size; set++) {
                    if ((set & (1 << bit)) != 0) {
                        within[set] += within[set ^ (1 << bit)];
                    }
                }
            }
            double[] below = null;
            for (int i = 0; i < counts.length; i++) {
                if (ties[i] == 0) {
                    continue;
                }
                if (below == null) {
                    below = new double[size];
                    Arrays.fill(below, Double.NaN);
                    below[0] = 1;
                }
                fillBelow(below, ties[i]);
                int prefer = (int) prefers[i];
                int tie = (int) ties[i];
                int rest = (size - 1) & ~prefer;
                // Every S holding `prefer`: prefer | (a subset of the rest).
                for (int extra = rest; ; extra = (extra - 1) & rest) {
                    int set = prefer | extra;
                    within[set] += approximateCounts[i] * below[tie & ~set];
                    if (extra == 0) {
                        break;
                    }
                }
            }
            return within;
        }

        /** below(R) for every subset R of {@code tie}, smaller ones first, where not yet known. */
        private void fillBelow(double[] below, long tie) {
            int k = Long.bitCount(tie);
            for (long a = 1; a < 1L << k; a++) {
                int r = (int) Long.expand(a, tie);
                if (!Double.isNaN(below[r])) {
                    continue;
                }
                double kept = 0;
                double deciding = 0;
                for (int d = 0; d < counts.length; d++) {
                    if ((r & ~ties[d]) == 0) {
                        continue;
                    }
                    deciding += approximateCounts[d];
                    if ((prefers[d] & r) == 0) {
                        // A proper subset of r, so numerically smaller and already filled.
                        kept += approximateCounts[d] * below[(int) (r & ties[d])];
                    }
                }
                below[r] = deciding > 0 ? kept / deciding : 1.0 / (Long.bitCount(r) + 1);
            }
        }

        /** The weight preferring no member outside {@code inside}, exactly. */
        Fraction exactWithin(long inside) {
            Fraction sum = Fraction.ZERO;
            for (int i = 0; i < counts.length; i++) {
                if ((prefers[i] & ~inside) == 0) {
                    Fraction count = Fraction.of(counts[i]);
                    sum = sum.add(ties[i] == 0 ? count : count.multiply(exactBelow(ties[i] & ~inside)));
                }
            }
            return sum;
        }

        /** The weight preferring no member outside {@code inside}, approximately. */
        double approximateWithin(long inside) {
            double sum = 0;
            for (int i = 0; i < counts.length; i++) {
                if ((prefers[i] & ~inside) == 0) {
                    sum += ties[i] == 0 ? approximateCounts[i]
                            : approximateCounts[i] * approximateBelow(ties[i] & ~inside);
                }
            }
            return sum;
        }

        /** below(r), approximately, for any r; see the class comment. */
        private double approximateBelow(long r) {
            if (r == 0) {
                return 1;
            }
            Double known = approximateBelow.get(r);
            if (known != null) {
                return known;
            }
            double kept = 0;
            double deciding = 0;
            for (int d = 0; d < counts.length; d++) {
                if ((r & ~ties[d]) == 0) {
                    continue;
                }
                deciding += approximateCounts[d];
                if ((prefers[d] & r) == 0) {
                    kept += approximateCounts[d] * approximateBelow(r & ties[d]);
                }
            }
            double share = deciding > 0 ? kept / deciding : 1.0 / (Long.bitCount(r) + 1);
            approximateBelow.put(r, share);
            return share;
        }

        /** below(r), exactly; see the class comment. */
        private Fraction exactBelow(long r) {
            if (r == 0) {
                return Fraction.ONE;
            }
            Fraction known = exactBelow.get(r);
            if (known != null) {
                return known;
            }
            BigInteger deciding = BigInteger.ZERO;
            Map<Long, BigInteger> kept = new HashMap<>();
            for (int d = 0; d < counts.length; d++) {
                if ((r & ~ties[d]) == 0) {
                    continue;
                }
                deciding = deciding.add(counts[d]);
                if ((prefers[d] & r) == 0) {
                    kept.merge(r & ties[d], counts[d], BigInteger::add);
                }
            }
            Fraction share;
            if (deciding.signum() == 0) {
                share = Fraction.of(1, Long.bitCount(r) + 1);
            } else {
                share = Fraction.ZERO;
                for (Map.Entry<Long, BigInteger> rest : kept.entrySet()) {
                    share = share.add(Fraction.of(rest.getValue()).multiply(exactBelow(rest.getKey())));
                }
                share = share.divide(Fraction.of(deciding));
            }
            exactBelow.put(r, share);
            return share;
        }
    }

    /**
     * Numbers distinct {@code long} keys 0, 1, 2... in order of first appearance: a primitive
     * open-addressing table, since boxing every key in a {@code HashMap} dominated the count.
     */
    static final class LongIndex {
        private final long[] keys;
        private final int[] slots;
        private final int shift;
        private int size;

        LongIndex(int capacity) {
            int length = Integer.highestOneBit(Math.max(2, capacity) * 2 - 1) * 2;
            keys = new long[length];
            slots = new int[length];
            shift = Long.SIZE - Integer.numberOfTrailingZeros(length);
            Arrays.fill(slots, -1);
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
            return size++;
        }
    }

    // ---- committees and paths ----------------------------------------------------------

    /**
     * The committee graph: every committee's links to the committees one swap away, with the
     * margin of each. Every link has a reverse with the opposite margin, both decided within one
     * contest, so each contest's margins are made once and a link and its reverse share one: a
     * target stored as {@code ~y} means the link's margin is the negative of the one held.
     * Margins are held as their {@link SignedRanks}, in the same order, and negated the same.
     */
    private record Graph(int[][] targets, int[][] margins) {

        static Graph of(List<int[]> committees, Fraction[][] supports, int n, int seats, Binomials binomials,
                Interruption.Watch watch) {
            int[][] targets = new int[committees.size()][];
            int[][] margins = new int[committees.size()][];
            // pairRanks[s][pair(i, j)], i < j: N[contest s without i; i] - N[contest s without j; j],
            // ranked. The margins are made as they're ranked, never all held at once.
            int size = seats + 1;
            int[] first = new int[size * (size - 1) / 2];
            int[] second = new int[first.length];
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    first[pair(i, j, size)] = i;
                    second[pair(i, j, size)] = j;
                }
            }
            int[] lengths = new int[supports.length];
            Arrays.fill(lengths, first.length);
            int[][] pairRanks = SignedRanks.of(lengths,
                    (s, p) -> supports[s][first[p]].subtract(supports[s][second[p]]));
            watch.run(() -> IntStream.range(0, committees.size()).parallel().forEach(x -> {
                try (Interruption.Piece _ = watch.enter()) {
                    int[] committee = committees.get(x);
                    boolean[] in = new boolean[n];
                    for (int member : committee) {
                        in[member] = true;
                    }
                    int links = seats * (n - seats);
                    int[] to = new int[links];
                    int[] margin = new int[links];
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
                                margin[l] = pairRanks[s][pair(cAt, bAt, seats + 1)];
                            } else {
                                to[l] = ~y;
                                margin[l] = pairRanks[s][pair(bAt, cAt, seats + 1)];
                            }
                            l++;
                        }
                    }
                    targets[x] = to;
                    margins[x] = margin;
                }
            }));
            return new Graph(targets, margins);
        }

        /** The index of positions {@code i < j} among a contest of {@code size}'s pairs. */
        private static int pair(int i, int j, int size) {
            return i * (2 * size - i - 1) / 2 + j - i - 1;
        }

        /** The committees no other committee beats, found from {@code start} (see the class javadoc). */
        List<Integer> unbeaten(int start) {
            int current = start;
            int[] from;
            int[] to;
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
                if (x == current || (from[x] == to[x]
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
        private static int beaterOf(int x, int[] from, int[] to) {
            int beater = -1;
            for (int y = 0; y < from.length; y++) {
                if (y != x && to[y] > from[y] && (beater < 0 || to[y] > to[beater])) {
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
        private int[] widest(int source, boolean reverse) {
            Interruption.check();
            int[] best = new int[targets.length];
            Arrays.fill(best, Integer.MIN_VALUE); // below every margin: not reached yet
            boolean[] done = new boolean[targets.length];
            done[source] = true;
            Reaches queue = new Reaches();
            relax(source, Integer.MAX_VALUE, reverse, best, done, queue);
            while (!queue.isEmpty()) {
                long reach = queue.poll();
                int x = (int) reach;
                if (done[x]) {
                    continue;
                }
                done[x] = true;
                relax(x, (int) (reach >> Integer.SIZE), reverse, best, done, queue);
            }
            return best;
        }

        private void relax(int x, int strength, boolean reverse, int[] best, boolean[] done, Reaches queue) {
            for (int l = 0; l < targets[x].length; l++) {
                int target = targets[x][l];
                int y = target < 0 ? ~target : target;
                if (done[y]) {
                    continue;
                }
                // Reversed, the link used is y -> x, whose margin is the opposite of x -> y's.
                int margin = (target < 0) != reverse ? -margins[x][l] : margins[x][l];
                int through = Math.min(margin, strength);
                if (through > best[y]) {
                    best[y] = through;
                    queue.add(through, y);
                }
            }
        }
    }

    /** Committees reached, strongest first: a binary heap of each one's strength and index. */
    private static final class Reaches {
        private long[] heap = new long[64];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        void add(int strength, int committee) {
            if (size == heap.length) {
                heap = Arrays.copyOf(heap, size * 2);
            }
            long reach = (long) strength << Integer.SIZE | committee;
            int at = size++;
            while (at > 0 && heap[(at - 1) / 2] < reach) {
                heap[at] = heap[(at - 1) / 2];
                at = (at - 1) / 2;
            }
            heap[at] = reach;
        }

        /** The strongest, its strength in the high half and its committee in the low. */
        long poll() {
            long top = heap[0];
            long last = heap[--size];
            int at = 0;
            while (2 * at + 1 < size) {
                int child = 2 * at + 1;
                if (child + 1 < size && heap[child + 1] > heap[child]) {
                    child++;
                }
                if (heap[child] <= last) {
                    break;
                }
                heap[at] = heap[child];
                at = child;
            }
            heap[at] = last;
            return top;
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
