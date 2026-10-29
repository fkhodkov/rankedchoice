package info.fkhodkov.rankedchoice.schulzestv;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;

/**
 * Every contest of a count's candidates reduced to the order each ballot gives its members (as
 * {@link SchulzeStvCounter.Reduced#of} does one), built up a member at a time rather than from
 * every ballot afresh.
 *
 * <p>Contests are listed in order, so each is its parent, the same members less the last, plus
 * a member x numbered above them all. A ballot that doesn't rank x puts it level with the
 * parent's members it doesn't rank, below every one it does: its group's levels stand, and x
 * takes the level of how many members the group ranks, so the whole group moves at once. Only
 * the ballots ranking x are placed one by one. A contest then costs about its parent's groups
 * plus the ballots ranking x, rather than every ballot times every pair of members, which on
 * short ballots is a small part.
 *
 * <p>Within a contest, groups merge when every member's level is the same. Above the contests,
 * the groups also keep apart by how many members they rank: ranking them all with x last and
 * ranking them all but x give the same levels, but a further member goes below x on the first
 * and level with it on the second.
 */
final class ContestReductions {

    private final int candidates;
    private final int size;
    private final int bits;
    private final int[][] positions;
    private final long[] voters;
    /** {@code rankedBy[x]}: the ballots that rank candidate x. */
    private final int[][] rankedBy;
    /** {@code aheadOf[x][i]}: the candidates ballot {@code rankedBy[x][i]} ranks ahead of x, as a mask. */
    private final long[][] aheadOf;
    /** {@code behindOf[x][i]}: those it puts behind x, unranked included. */
    private final long[][] behindOf;
    /** A bit at each member's level: 0, bits, 2 * bits, .... */
    private final long levelBits;

    /** One stage's ballots in groups: each group's members' levels, packed, how many it ranks, and its voters. */
    private record Node(int[] group, long[] levels, int[] ranked, long[] voters, int groups) {
    }

    private ContestReductions(int candidates, int size, SchulzeStvCounter.IndexedBallots ballots) {
        this.candidates = candidates;
        this.size = size;
        bits = bits(size);
        positions = ballots.positions();
        voters = ballots.smallCounts();
        rankedBy = IntStream.range(0, candidates).mapToObj(x -> IntStream.range(0, positions.length)
                .filter(b -> positions[b][x] != Integer.MAX_VALUE).toArray()).toArray(int[][]::new);
        aheadOf = new long[candidates][];
        behindOf = new long[candidates][];
        for (int x = 0; x < candidates; x++) {
            aheadOf[x] = new long[rankedBy[x].length];
            behindOf[x] = new long[rankedBy[x].length];
            for (int i = 0; i < rankedBy[x].length; i++) {
                int[] position = positions[rankedBy[x][i]];
                for (int c = 0; c < candidates; c++) {
                    if (position[c] < position[x]) {
                        aheadOf[x][i] |= 1L << c;
                    } else if (position[c] > position[x]) {
                        behindOf[x][i] |= 1L << c;
                    }
                }
            }
        }
        long spread = 0;
        for (int m = 0; m < size; m++) {
            spread |= 1L << (bits * m);
        }
        levelBits = spread;
    }

    private static int bits(int size) {
        return Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(size - 1));
    }

    /**
     * Whether contests of {@code size} can be built this way: levels and the count ranked packed
     * in a {@code long}, the candidates in a {@code long} mask, and the voters counted in
     * {@code long}s.
     */
    static boolean applies(int size, SchulzeStvCounter.IndexedBallots ballots) {
        int candidates = ballots.positions().length == 0 ? 0 : ballots.positions()[0].length;
        return size >= 2 && bits(size) * (size + 1) <= Long.SIZE && candidates <= Long.SIZE
                && ballots.smallCounts() != null;
    }

    /**
     * Passes every contest of {@code size} of the candidates {@code 0..candidates - 1}, sorted, to
     * {@code use} with its reduction, in parallel. Requires {@link #applies}.
     */
    static void forEach(int candidates, int size, SchulzeStvCounter.IndexedBallots ballots,
            BiConsumer<int[], SchulzeStvCounter.Reduced> use) {
        if (!applies(size, ballots)) {
            throw new IllegalArgumentException("contests of " + size + " can't be built up this way");
        }
        new ContestReductions(candidates, size, ballots).run(use);
    }

    private void run(BiConsumer<int[], SchulzeStvCounter.Reduced> use) {
        // Each first pair of members (or first member, for contests of two) is a task of its own.
        int split = Math.min(2, size - 1);
        List<int[]> starts = new ArrayList<>();
        int[] start = new int[split];
        collect(start, 0, starts);
        starts.parallelStream().forEach(prefix -> {
            int[] members = new int[size];
            Node node = root();
            for (int depth = 0; depth < split; depth++) {
                members[depth] = prefix[depth];
                node = refine(node, members, depth, false);
            }
            descend(node, members, split, use);
        });
    }

    /** Every sorted prefix of {@code start.length} members that a contest can still be built on. */
    private void collect(int[] start, int depth, List<int[]> into) {
        if (depth == start.length) {
            into.add(start.clone());
            return;
        }
        for (int x = depth == 0 ? 0 : start[depth - 1] + 1; x <= candidates - (size - depth); x++) {
            start[depth] = x;
            collect(start, depth + 1, into);
        }
    }

    private Node root() {
        long total = 0;
        for (long count : voters) {
            total += count;
        }
        return new Node(new int[positions.length], new long[] {0}, new int[] {0}, new long[] {total}, 1);
    }

    private void descend(Node node, int[] members, int depth, BiConsumer<int[], SchulzeStvCounter.Reduced> use) {
        boolean leaf = depth + 1 == size;
        for (int x = depth == 0 ? 0 : members[depth - 1] + 1; x <= candidates - (size - depth); x++) {
            members[depth] = x;
            Node child = refine(node, members, depth, leaf);
            if (leaf) {
                use.accept(members.clone(), reduced(child));
            } else {
                descend(child, members, depth + 1, use);
            }
        }
    }

    /** The groups once {@code members[depth]} joins {@code members[0..depth - 1]}. */
    private Node refine(Node parent, int[] members, int depth, boolean leaf) {
        int x = members[depth];
        int[] ranking = rankedBy[x];
        int capacity = parent.groups() + ranking.length;
        SchulzeStvCounter.LongIndex index = new SchulzeStvCounter.LongIndex(capacity);
        long[] levels = new long[capacity];
        int[] ranked = new int[capacity];
        long[] counts = new long[capacity];
        int groups = 0;
        int shift = bits * depth;
        // Contests merge on the levels alone; above them, the count ranked goes in the top bits.
        int rankedShift = bits * size;

        // Every ballot as if it didn't rank x: x below the members its group ranks.
        int[] childOf = new int[parent.groups()];
        for (int g = 0; g < parent.groups(); g++) {
            long key = parent.levels()[g] | (long) parent.ranked()[g] << shift;
            int c = index.of(leaf ? key : key | (long) parent.ranked()[g] << rankedShift);
            if (c == groups) {
                levels[groups] = key;
                ranked[groups++] = parent.ranked()[g];
            }
            counts[c] += parent.voters()[g];
            childOf[g] = c;
        }
        int[] group = null;
        if (!leaf) {
            group = new int[positions.length];
            for (int b = 0; b < group.length; b++) {
                group[b] = childOf[parent.group()[b]];
            }
        }

        // Then the ballots that do rank x, moved to where they belong. Their masks, read in
        // order, say which members they rank ahead of x and which behind; reading each ballot's
        // positions instead was most of the count, nearly every one a cache miss.
        long memberMask = 0;
        for (int m = 0; m < depth; m++) {
            memberMask |= 1L << members[m];
        }
        long[] aheads = aheadOf[x];
        long[] behinds = behindOf[x];
        for (int i = 0; i < ranking.length; i++) {
            int b = ranking[i];
            int g = parent.group()[b];
            // x is now ahead of the members behind it: one more level each.
            long behind = Long.expand(Long.compress(behinds[i], memberMask), levelBits);
            int ahead = Long.bitCount(aheads[i] & memberMask);
            long key = (parent.levels()[g] + behind) | (long) ahead << shift;
            int r = parent.ranked()[g] + 1;
            int c = index.of(leaf ? key : key | (long) r << rankedShift);
            if (c == groups) {
                levels[groups] = key;
                ranked[groups++] = r;
            }
            counts[childOf[g]] -= voters[b];
            counts[c] += voters[b];
            if (group != null) {
                group[b] = c;
            }
        }
        return new Node(group, levels, ranked, counts, groups);
    }

    /** A contest's groups, those with voters, as {@link SchulzeStvCounter.Reduced#of} gives them. */
    private SchulzeStvCounter.Reduced reduced(Node node) {
        long mask = (1L << bits) - 1;
        int[][] levels = new int[node.groups()][];
        BigInteger[] counts = new BigInteger[node.groups()];
        int kept = 0;
        for (int g = 0; g < node.groups(); g++) {
            if (node.voters()[g] == 0) {
                continue;
            }
            int[] level = new int[size];
            for (int j = 0; j < size; j++) {
                level[j] = (int) (node.levels()[g] >>> (bits * j) & mask);
            }
            levels[kept] = level;
            counts[kept++] = BigInteger.valueOf(node.voters()[g]);
        }
        return new SchulzeStvCounter.Reduced(Arrays.copyOf(levels, kept), Arrays.copyOf(counts, kept));
    }
}
