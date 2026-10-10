package info.fkhodkov.rankedchoice.schulzestv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;
import info.fkhodkov.rankedchoice.schulze.SchulzeMethod;
import info.fkhodkov.rankedchoice.schulze.SchulzeResult;

/**
 * Orders every candidate by the Schulze proportional ranking (Markus Schulze, "The Schulze
 * Method of Voting", arXiv:1804.02973, section 9.1): a party list whose first k places are as
 * proportional as they can be for every k, built top down.
 *
 * <p>With a1..a(k-1) already placed, each remaining pair b, c is compared by H[b,c] =
 * N[{a1..a(k-1), b}; c], the support Schulze STV gives a committee against an outsider (see
 * {@link SchulzeStvCounter}), with proportional completion over that contest's candidates
 * only. The single-winner Schulze method runs on H, links compared by margin, H[b,c] - H[c,b],
 * as the paper's worked example does; its one potential winner takes place k. If there are
 * several, the one the single-winner Schulze ranking of all the candidates ({@link
 * SchulzeMethod}) puts highest takes it, declared order settling a tie there. The last place
 * goes to whoever is left.
 *
 * <p>The first place is decided the same way, on H[b,c] = N[{b}; c], the completed pairwise
 * comparison. Schulze's own program (singl04g.cpp) differs in three ways, so it isn't a
 * reference for every place: it gives the first place to the top of its single-winner ranking,
 * compares H's links by winning votes rather than margin, and breaks ties with a ranking drawn
 * from a random ballot order. The supports, H itself, follow from who's already placed alone.
 *
 * <p>Each place needs a support per remaining pair: C(r, 2) contests for r candidates left, each
 * as big as the list so far plus two. Small committees use the closed form, larger ones
 * {@link LargeCommitteeSupport}; {@link #MAX_CANDIDATES} caps the election.
 */
public final class SchulzeProportionalRanking {

    /** Contests are bit masks of at most 63 members, with the outsider. */
    public static final int MAX_CANDIDATES = 64;

    /** Committees larger than this have their contests counted one at a time; see {@link #comparisons}. */
    static final int SEQUENTIAL_SEATS = 16;

    private SchulzeProportionalRanking() {
    }

    public static SchulzeProportionalResult count(List<Candidate> candidates, List<Ballot> ballots) {
        int n = candidates.size();
        if (n < 1) {
            throw new IllegalArgumentException("there must be at least one candidate");
        }
        if (n > MAX_CANDIDATES) {
            throw new IllegalArgumentException("Schulze proportional ranking is capped at " + MAX_CANDIDATES
                    + " candidates, got " + n);
        }
        SchulzeResult singleWinner = SchulzeMethod.count(candidates, ballots);
        int[] tieBreakPlace = tieBreakPlaces(candidates, singleWinner);
        SchulzeStvCounter.IndexedBallots indexed = SchulzeStvCounter.IndexedBallots.of(candidates, ballots);

        List<Integer> placed = new ArrayList<>();
        List<SchulzeProportionalResult.Place> places = new ArrayList<>();
        boolean[] done = new boolean[n];
        while (placed.size() < n) {
            int[] remaining = IntStream.range(0, n).filter(c -> !done[c]).toArray();
            if (remaining.length == 1) {
                done[remaining[0]] = true;
                placed.add(remaining[0]);
                places.add(new SchulzeProportionalResult.Place(placed.size(), candidates.get(remaining[0]),
                        Map.of(), List.of(candidates.get(remaining[0]))));
                break;
            }
            Fraction[][] h = comparisons(placed, remaining, indexed, n);
            List<Integer> potential = potentialWinners(remaining, h);
            int winner = potential.stream().min((a, b) -> tieBreakPlace[a] != tieBreakPlace[b]
                    ? Integer.compare(tieBreakPlace[a], tieBreakPlace[b]) : Integer.compare(a, b)).orElseThrow();
            Map<Candidate, Map<Candidate, Fraction>> matrix = new LinkedHashMap<>();
            for (int b : remaining) {
                Map<Candidate, Fraction> row = new LinkedHashMap<>();
                for (int c : remaining) {
                    if (b != c) {
                        row.put(candidates.get(c), h[b][c]);
                    }
                }
                matrix.put(candidates.get(b), row);
            }
            done[winner] = true;
            placed.add(winner);
            places.add(new SchulzeProportionalResult.Place(placed.size(), candidates.get(winner), matrix,
                    potential.stream().map(candidates::get).toList()));
        }
        return new SchulzeProportionalResult(placed.stream().map(candidates::get).toList(), places, singleWinner);
    }

    /** H[b][c] = N[placed + b; c] for every remaining b != c, one contest per pair. */
    private static Fraction[][] comparisons(List<Integer> placed, int[] remaining,
            SchulzeStvCounter.IndexedBallots indexed, int n) {
        Fraction[][] h = new Fraction[n][n];
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < remaining.length; i++) {
            for (int j = i + 1; j < remaining.length; j++) {
                pairs.add(new int[] {remaining[i], remaining[j]});
            }
        }
        // A large contest can take hundreds of megabytes (the closed form's 2^M arrays, or a
        // listed-out completion): those go one at a time.
        boolean large = placed.size() + 1 > SEQUENTIAL_SEATS;
        List<Fraction[]> supports = (large ? pairs.stream() : pairs.parallelStream()).map(pair -> {
            int[] contest = new int[placed.size() + 2];
            for (int i = 0; i < placed.size(); i++) {
                contest[i] = placed.get(i);
            }
            contest[placed.size()] = pair[0];
            contest[placed.size() + 1] = pair[1];
            Arrays.sort(contest);
            SchulzeStvCounter.Reduced reduced = SchulzeStvCounter.Reduced.of(contest, indexed);
            int atB = Arrays.binarySearch(contest, pair[0]);
            int atC = Arrays.binarySearch(contest, pair[1]);
            // {N[placed + b; c], N[placed + c; b]}
            return new Fraction[] {SchulzeStvCounter.support(reduced, atC), SchulzeStvCounter.support(reduced, atB)};
        }).toList();
        for (int p = 0; p < pairs.size(); p++) {
            int b = pairs.get(p)[0];
            int c = pairs.get(p)[1];
            h[b][c] = supports.get(p)[0];
            h[c][b] = supports.get(p)[1];
        }
        return h;
    }

    /**
     * The single-winner Schulze method's potential winners on H, links compared by margin: those
     * whose strongest path to every other is at least as strong as the other's back.
     */
    private static List<Integer> potentialWinners(int[] remaining, Fraction[][] h) {
        int r = remaining.length;
        Fraction[][] path = new Fraction[r][r];
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < r; j++) {
                if (i != j) {
                    path[i][j] = h[remaining[i]][remaining[j]].subtract(h[remaining[j]][remaining[i]]);
                }
            }
        }
        for (int via = 0; via < r; via++) {
            for (int i = 0; i < r; i++) {
                if (i == via) {
                    continue;
                }
                for (int j = 0; j < r; j++) {
                    if (j == via || j == i) {
                        continue;
                    }
                    Fraction through = min(path[i][via], path[via][j]);
                    if (through.compareTo(path[i][j]) > 0) {
                        path[i][j] = through;
                    }
                }
            }
        }
        List<Integer> potential = new ArrayList<>();
        for (int i = 0; i < r; i++) {
            boolean unbeaten = true;
            for (int j = 0; j < r && unbeaten; j++) {
                if (i != j && path[j][i].compareTo(path[i][j]) > 0) {
                    unbeaten = false;
                }
            }
            if (unbeaten) {
                potential.add(remaining[i]);
            }
        }
        return potential;
    }

    private static Fraction min(Fraction a, Fraction b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** Each candidate's place in the single-winner ranking: equal places for a tie. */
    private static int[] tieBreakPlaces(List<Candidate> candidates, SchulzeResult ranking) {
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
}
