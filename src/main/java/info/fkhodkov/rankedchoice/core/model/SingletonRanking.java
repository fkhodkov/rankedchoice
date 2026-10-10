package info.fkhodkov.rankedchoice.core.model;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.RandomAccess;
import java.util.Set;

/**
 * A ranking with one candidate at each rank, kept as the candidates' indices in a {@link Table}
 * rather than as a list of references: two bytes a rank instead of four, which on a million
 * ballots ranking 40 candidates is about 90 MB of the ballots as read. Each rank is the table's
 * shared {@code Set.of(candidate)}, so it reads exactly as the list {@link Ballot} would
 * otherwise keep, equality and hash code included.
 */
public final class SingletonRanking extends AbstractList<Set<Candidate>> implements RandomAccess {

    private final List<Set<Candidate>> alone;
    private final char[] ranks;

    private SingletonRanking(List<Set<Candidate>> alone, char[] ranks) {
        this.alone = alone;
        this.ranks = ranks;
    }

    @Override
    public Set<Candidate> get(int index) {
        return alone.get(ranks[index]);
    }

    @Override
    public int size() {
        return ranks.length;
    }

    /** The candidates of one election, each with the one-candidate set its rankings share. */
    public static final class Table {

        private final List<Candidate> candidates;
        private final List<Set<Candidate>> alone;

        public Table(List<Candidate> candidates) {
            this.candidates = List.copyOf(candidates);
            this.alone = this.candidates.stream().map(Set::of).toList();
        }

        /** The set holding just the candidate at {@code index}. */
        public Set<Candidate> alone(int index) {
            return alone.get(index);
        }

        /**
         * The ranking of the candidates at these indices, in order; no candidate may appear twice.
         * It's a SingletonRanking unless there are more candidates than a {@code char} can index.
         */
        public List<Set<Candidate>> ranking(int[] indices) {
            char[] ranks = new char[indices.length];
            long[] seen = new long[(candidates.size() + 63) / 64];
            for (int i = 0; i < indices.length; i++) {
                int index = indices[i];
                if ((seen[index >> 6] & 1L << index) != 0) {
                    throw new IllegalArgumentException(
                            "candidate " + candidates.get(index).id() + " appears more than once in the same ballot");
                }
                seen[index >> 6] |= 1L << index;
                ranks[i] = (char) index;
            }
            if (candidates.size() > Character.MAX_VALUE + 1) {
                return Arrays.stream(indices).mapToObj(alone::get).toList();
            }
            return new SingletonRanking(alone, ranks);
        }
    }
}
