package info.fkhodkov.rankedchoice.schulzestv;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * The support {@code N[committee; g]} for committees too large for the closed form's 2^M subsets
 * (see {@link SchulzeStvCounter}), up to 63 members. It needs only f(J), the completed weight
 * preferring some member of J to g, at a few hundred sets J, which {@link
 * SchulzeStvCounter.Completion} works out without listing the completion out.
 *
 * <p>f is submodular, a mixture of coverage functions, and the support is the minimum over
 * non-empty J of f(J) / |J|: the largest t for which t at every member lies in f's polymatroid.
 * Wolfe's minimum-norm-point algorithm (Fujishige's approach to submodular minimisation), in
 * {@code double}, finds the point x of f's base polytope nearest the origin. Every point of
 * the polytope bounds the support from below by its smallest coordinate, since x(J) is at most
 * f(J); and at the nearest point, the members on that smallest coordinate are a set J whose
 * f(J) / |J| equals it. So when the ratio of a set of the smallest coordinates comes within
 * {@link #CERTIFIED} of x's smallest coordinate, relatively, that set's ratio is the support to
 * that precision. The support is then computed exactly, by the completion's exact figures, at
 * every such set whose ratio is near the smallest, and the smallest kept. It is the exact
 * support unless two different sets' ratios come within about 10^-11 of each other,
 * relatively, without being equal. A contest the search can't certify is refused.
 */
final class MinimumNormSupport {

    /** How close, relative to the total weight, the upper and lower bounds must come. */
    static final double CERTIFIED = 1e-11;

    /** Sets whose ratio is this close to the smallest, relatively, are computed exactly. */
    private static final double CANDIDATE_TOLERANCE = 1e-9;

    private static final int MAX_ITERATIONS = 20_000;

    private final SchulzeStvCounter.Completion completion;
    private final int seats;
    private final long all;
    private final double total;

    private MinimumNormSupport(SchulzeStvCounter.Completion completion) {
        this.completion = completion;
        seats = completion.seats();
        all = (1L << seats) - 1;
        total = completion.total().doubleValue();
    }

    /**
     * N[the contest's other members; member k], from ballots reduced to the contest ({@code
     * levels[b][j]} is member j's level on group b, {@code counts[b]} its voters).
     */
    static Fraction of(int[][] levels, BigInteger[] counts, int k) {
        return new MinimumNormSupport(SchulzeStvCounter.Completion.against(levels, counts, k)).support();
    }

    private Fraction support() {
        if (total == 0) {
            return Fraction.ZERO;
        }
        double[] x = nearestPoint();
        Integer[] order = order(x);
        double lower = x[order[0]];

        // The sets of the j smallest coordinates; the support's own set is one of them.
        double[] ratios = new double[seats];
        long members = 0;
        double smallest = Double.POSITIVE_INFINITY;
        for (int j = 0; j < seats; j++) {
            members |= 1L << order[j];
            ratios[j] = covered(members) / (j + 1);
            smallest = Math.min(smallest, ratios[j]);
        }
        if (smallest - lower > CERTIFIED * total) {
            throw new IllegalStateException("the support of a contest of " + (seats + 1)
                    + " candidates couldn't be pinned down in double precision (bounds " + lower + " and "
                    + smallest + ")");
        }

        Fraction exactTotal = Fraction.of(completion.total());
        Fraction best = null;
        members = 0;
        for (int j = 0; j < seats; j++) {
            members |= 1L << order[j];
            if (ratios[j] <= smallest + CANDIDATE_TOLERANCE * total) {
                Fraction exact = exactTotal.subtract(completion.exactWithin(all & ~members))
                        .divide(Fraction.of(j + 1));
                if (best == null || exact.compareTo(best) < 0) {
                    best = exact;
                }
            }
        }
        return best;
    }

    /** f(J), approximately. */
    private double covered(long members) {
        return total - completion.approximateWithin(all & ~members);
    }

    /** The members by coordinate, smallest first, ties by number. */
    private Integer[] order(double[] x) {
        Integer[] order = IntStream.range(0, seats).boxed().toArray(Integer[]::new);
        Arrays.sort(order, Comparator.<Integer>comparingDouble(j -> x[j]).thenComparingInt(j -> j));
        return order;
    }

    /**
     * The base polytope's vertex that the order of {@code x} gives, the greedy algorithm's:
     * each member, smallest coordinate first, takes what it adds to f. It minimises x . q over
     * the polytope.
     */
    private double[] vertex(double[] x) {
        double[] q = new double[seats];
        long members = 0;
        double before = 0;
        for (int j : order(x)) {
            members |= 1L << j;
            double now = covered(members);
            q[j] = now - before;
            before = now;
        }
        return q;
    }

    /**
     * Wolfe's algorithm: x is kept as a convex combination of vertices, each step adding the
     * vertex the order of x gives and moving x to the nearest point of the vertices' affine hull,
     * or as far toward it as the combination stays convex, dropping the vertices that leave it.
     * It stops when no vertex brings x nearer: x . x - x . q, which bounds the squared distance
     * to the nearest point, is down to rounding error.
     */
    private double[] nearestPoint() {
        List<double[]> vertices = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double[] x = vertex(new double[seats]);
        vertices.add(x);
        weights.add(1.0);
        double tolerance = 1e-15 * total * total;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            double[] q = vertex(x);
            if (dot(x, x) - dot(x, q) <= tolerance) {
                break;
            }
            vertices.add(q);
            weights.add(0.0);
            while (true) {
                double[] affine = affineWeights(vertices);
                if (affine == null) {
                    // The new vertex is (numerically) in the others' affine hull: no progress.
                    vertices.removeLast();
                    weights.removeLast();
                    return x;
                }
                boolean convex = true;
                for (double a : affine) {
                    convex &= a > 0;
                }
                if (convex) {
                    for (int i = 0; i < affine.length; i++) {
                        weights.set(i, affine[i]);
                    }
                    x = combination(vertices, weights);
                    break;
                }
                double theta = 1;
                for (int i = 0; i < affine.length; i++) {
                    if (affine[i] <= 0) {
                        theta = Math.min(theta, weights.get(i) / (weights.get(i) - affine[i]));
                    }
                }
                for (int i = affine.length - 1; i >= 0; i--) {
                    double w = (1 - theta) * weights.get(i) + theta * affine[i];
                    if (w <= 1e-15) {
                        vertices.remove(i);
                        weights.remove(i);
                    } else {
                        weights.set(i, w);
                    }
                }
                normalise(weights);
                x = combination(vertices, weights);
            }
        }
        return x;
    }

    /**
     * The weights, summing to 1, of the point of the vertices' affine hull nearest the origin:
     * (Q'Q + 11') a = 1, scaled to sum 1. Null if the system is singular.
     */
    private static double[] affineWeights(List<double[]> vertices) {
        int n = vertices.size();
        double[][] a = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double g = dot(vertices.get(i), vertices.get(j)) + 1;
                a[i][j] = g;
                a[j][i] = g;
            }
            a[i][n] = 1;
        }
        double scale = 0;
        for (double[] row : a) {
            scale = Math.max(scale, Math.abs(row[0]));
        }
        for (int c = 0; c < n; c++) {
            int pivot = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[pivot][c])) {
                    pivot = r;
                }
            }
            if (Math.abs(a[pivot][c]) <= 1e-13 * scale) {
                return null;
            }
            double[] swap = a[c];
            a[c] = a[pivot];
            a[pivot] = swap;
            for (int r = 0; r < n; r++) {
                if (r != c && a[r][c] != 0) {
                    double factor = a[r][c] / a[c][c];
                    for (int k = c; k <= n; k++) {
                        a[r][k] -= factor * a[c][k];
                    }
                }
            }
        }
        double[] weights = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            weights[i] = a[i][n] / a[i][i];
            sum += weights[i];
        }
        for (int i = 0; i < n; i++) {
            weights[i] /= sum;
        }
        return weights;
    }

    private static void normalise(List<Double> weights) {
        double sum = weights.stream().mapToDouble(Double::doubleValue).sum();
        weights.replaceAll(w -> w / sum);
    }

    private double[] combination(List<double[]> vertices, List<Double> weights) {
        double[] x = new double[seats];
        for (int i = 0; i < vertices.size(); i++) {
            double w = weights.get(i);
            double[] q = vertices.get(i);
            for (int j = 0; j < seats; j++) {
                x[j] += w * q[j];
            }
        }
        return x;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
