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
 * over 2^M subsets, so {@link #MAX_COMMITTEES} and {@link #MAX_SEATS} cap the election rather
 * than let it run for hours.
 */
public final class SchulzeStvCounter {

    /** See the class javadoc's note on cost. */
    public static final int MAX_COMMITTEES = 20_000;

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

        List<SchulzeStvResult.Support> supportList = new ArrayList<>(contests.size() * (seats + 1));
        for (int s = 0; s < contests.size(); s++) {
            int[] contest = contests.get(s);
            for (int k = 0; k <= seats; k++) {
                Set<Candidate> committee = new LinkedHashSet<>();
                for (int member : contest) {
                    if (member != contest[k]) {
                        committee.add(candidates.get(member));
                    }
                }
                supportList.add(new SchulzeStvResult.Support(
                        committee, candidates.get(contest[k]), supports[s][k]));
            }
        }
        return new SchulzeStvResult(supportList, toSets(potential, committees, candidates),
                toSets(winners, committees, candidates), ranking);
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
     * other indifference.
     */
    private record IndexedBallots(int[][] positions, BigInteger[] counts) {

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
            return new IndexedBallots(positions.toArray(int[][]::new), counts.toArray(BigInteger[]::new));
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

    /** N[contest without member k; member k], for every k. */
    private static Fraction[] contestSupports(int[] contest, IndexedBallots ballots, int seats) {
        Fraction[] supports = new Fraction[seats + 1];
        int[] others = new int[seats];
        for (int k = 0; k <= seats; k++) {
            int g = contest[k];
            for (int j = 0, o = 0; j <= seats; j++) {
                if (j != k) {
                    others[o++] = contest[j];
                }
            }
            supports[k] = support(patterns(ballots, others, g), seats);
        }
        return supports;
    }

    /**
     * Each voter's pattern against outsider {@code g}, merged: bit j of the low {@code seats}
     * bits says they prefer {@code others[j]} to g, bit j of the high bits that they rank the
     * two level.
     */
    private static Map<Long, BigInteger> patterns(IndexedBallots ballots, int[] others, int g) {
        Map<Long, BigInteger> patterns = new HashMap<>();
        int seats = others.length;
        for (int b = 0; b < ballots.positions().length; b++) {
            int[] position = ballots.positions()[b];
            int against = position[g];
            long prefer = 0;
            long tie = 0;
            for (int j = 0; j < seats; j++) {
                int at = position[others[j]];
                if (at < against) {
                    prefer |= 1L << j;
                } else if (at == against) {
                    tie |= 1L << j;
                }
            }
            patterns.merge(prefer | tie << seats, ballots.counts()[b], BigInteger::add);
        }
        return patterns;
    }

    /** Completes {@code patterns} (section 8.1.1), then takes the closed-form support. */
    private static Fraction support(Map<Long, BigInteger> patterns, int seats) {
        long low = (1L << seats) - 1;
        Completion completion = new Completion(patterns, seats);
        List<Part> parts = new ArrayList<>(patterns.size());
        for (Map.Entry<Long, BigInteger> pattern : patterns.entrySet()) {
            long tie = pattern.getKey() >>> seats;
            parts.add(new Part(pattern.getKey() & low, pattern.getValue(),
                    tie == 0 ? Shares.WHOLE : completion.of(tie)));
        }
        return minimumOverSubsets(Shares.combine(parts, BigInteger.ONE), seats);
    }

    /**
     * Weights by pattern, as integer numerators over one common {@code denominator}: the sums
     * in proportional completion then need no gcd, which would otherwise dominate the count.
     * Not reduced, so the denominator can be larger than necessary, but every value is exact.
     */
    private record Shares(Map<Long, BigInteger> numerators, BigInteger denominator) {

        /** All the weight on the pattern with nothing further to place. */
        static final Shares WHOLE = new Shares(Map.of(0L, BigInteger.ONE), BigInteger.ONE);

        /** Each part's {@code count} times its {@code shares}, moved onto {@code prefix}, summed, divided by {@code divisor}. */
        static Shares combine(List<Part> parts, BigInteger divisor) {
            BigInteger common = BigInteger.ONE;
            for (Part part : parts) {
                BigInteger d = part.shares().denominator();
                if (!common.mod(d).equals(BigInteger.ZERO)) {
                    common = common.divide(common.gcd(d)).multiply(d);
                }
            }
            Map<Long, BigInteger> numerators = new HashMap<>();
            for (Part part : parts) {
                BigInteger scale = part.count().multiply(common.divide(part.shares().denominator()));
                for (Map.Entry<Long, BigInteger> share : part.shares().numerators().entrySet()) {
                    numerators.merge(part.prefix() | share.getKey(), share.getValue().multiply(scale), BigInteger::add);
                }
            }
            return new Shares(numerators, common.multiply(divisor));
        }
    }

    /** {@code count} voters whose pattern is {@code prefix}, then completed by {@code shares}. */
    private record Part(long prefix, BigInteger count, Shares shares) {
    }

    /**
     * How a voter ranking the members in {@code tie} level with g is completed: for each way
     * of placing them above (bit set) or below g, the share of the voter's weight that goes
     * there. Memoised by tie mask, since one tie mask serves every voter who has it.
     */
    private static final class Completion {
        private final Map<Long, BigInteger> original;
        private final int seats;
        private final Map<Long, Shares> memo = new HashMap<>();

        Completion(Map<Long, BigInteger> original, int seats) {
            this.original = original;
            this.seats = seats;
        }

        Shares of(long tie) {
            Shares known = memo.get(tie);
            if (known != null) {
                return known;
            }
            long low = (1L << seats) - 1;
            // The original voters who aren't level with g on every member of `tie`, by what
            // they say about those members.
            Map<Long, BigInteger> donors = new HashMap<>();
            BigInteger total = BigInteger.ZERO;
            for (Map.Entry<Long, BigInteger> pattern : original.entrySet()) {
                long theirTie = (pattern.getKey() >>> seats) & tie;
                if (theirTie != tie) {
                    long theirPrefer = pattern.getKey() & low & tie;
                    donors.merge(theirPrefer | theirTie << seats, pattern.getValue(), BigInteger::add);
                    total = total.add(pattern.getValue());
                }
            }
            Shares shares;
            if (total.signum() > 0) {
                List<Part> parts = new ArrayList<>(donors.size());
                for (Map.Entry<Long, BigInteger> donor : donors.entrySet()) {
                    long stillTied = donor.getKey() >>> seats;
                    // A donor itself level with g on some of them: that smaller tie is
                    // completed the same way, again from the original voters.
                    parts.add(new Part(donor.getKey() & low, donor.getValue(),
                            stillTied == 0 ? Shares.WHOLE : of(stillTied)));
                }
                shares = Shares.combine(parts, total);
            } else {
                // Nobody to copy: every order of the k tied members and g is equally likely.
                // An order with s of them above g is one of s! (k - s)! out of (k + 1)!.
                int k = Long.bitCount(tie);
                Map<Long, BigInteger> numerators = new HashMap<>();
                for (long above = tie; ; above = (above - 1) & tie) {
                    int s = Long.bitCount(above);
                    numerators.put(above, factorial(s).multiply(factorial(k - s)));
                    if (above == 0) {
                        break;
                    }
                }
                shares = new Shares(numerators, factorial(k + 1));
            }
            memo.put(tie, shares);
            return shares;
        }
    }

    private static BigInteger factorial(int n) {
        BigInteger result = BigInteger.ONE;
        for (int i = 2; i <= n; i++) {
            result = result.multiply(BigInteger.valueOf(i));
        }
        return result;
    }

    /**
     * min over non-empty J of supply(J) / |J|, where supply(J) is the weight preferring some
     * member of J: the total less the weight of patterns inside J's complement, summed over
     * all subsets at once.
     */
    private static Fraction minimumOverSubsets(Shares completed, int seats) {
        BigInteger denominator = completed.denominator();
        int size = 1 << seats;
        BigInteger[] within = new BigInteger[size];
        Arrays.fill(within, BigInteger.ZERO);
        for (Map.Entry<Long, BigInteger> pattern : completed.numerators().entrySet()) {
            within[pattern.getKey().intValue()] = within[pattern.getKey().intValue()].add(pattern.getValue());
        }
        for (int bit = 0; bit < seats; bit++) {
            for (int set = 0; set < size; set++) {
                if ((set & (1 << bit)) != 0) {
                    within[set] = within[set].add(within[set ^ (1 << bit)]);
                }
            }
        }
        BigInteger total = within[size - 1];
        BigInteger bestSupply = null;
        int bestSize = 0;
        for (int j = 1; j < size; j++) {
            BigInteger supply = total.subtract(within[(size - 1) & ~j]);
            int count = Integer.bitCount(j);
            if (bestSupply == null || supply.multiply(BigInteger.valueOf(bestSize))
                    .compareTo(bestSupply.multiply(BigInteger.valueOf(count))) < 0) {
                bestSupply = supply;
                bestSize = count;
            }
        }
        return Fraction.of(bestSupply, denominator.multiply(BigInteger.valueOf(bestSize)));
    }

    // ---- committees and paths ----------------------------------------------------------

    /**
     * The committee graph: every committee's links to the committees one swap away, with the
     * margin of each. Every link has a reverse with the opposite margin.
     */
    private record Graph(int[][] targets, Fraction[][] margins) {

        static Graph of(List<int[]> committees, Fraction[][] supports, int n, int seats, Binomials binomials) {
            int[][] targets = new int[committees.size()][];
            Fraction[][] margins = new Fraction[committees.size()][];
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
                        to[l] = binomials.rank(next);
                        margin[l] = supports[s][cAt].subtract(supports[s][bAt]);
                        l++;
                    }
                }
                targets[x] = to;
                margins[x] = margin;
            });
            return new Graph(targets, margins);
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
                if (x == current) {
                    unbeaten.add(x);
                } else if (from[x].compareTo(to[x]) == 0
                        && beaterOf(x, widest(x, false), widest(x, true)) < 0) {
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
                int y = targets[x][l];
                if (done[y]) {
                    continue;
                }
                // Reversed, the link used is y -> x, whose margin is the opposite of x -> y's.
                Fraction margin = reverse ? margins[x][l].negate() : margins[x][l];
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
