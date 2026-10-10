package info.fkhodkov.rankedchoice.borda;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.core.util.Fraction;

/**
 * A positional-scoring count: every ballot contributes points to each candidate it ranks,
 * per {@link BordaScoringRule}, and candidates are ordered by total score. A candidate a
 * ballot doesn't rank at all gets nothing from it, and one not in {@code candidates} (withdrawn)
 * is passed over, as if the ballot didn't rank them: the candidates after them move up.
 *
 * <p>Candidates tied for the same rank on a ballot (this project's {@link Ballot} model
 * supports tie groups directly) each receive the <em>average</em> of the scores their tied
 * positions would otherwise have been worth — e.g. two candidates tied for 2nd/3rd preference
 * on a 5-candidate ballot under {@link ClassicBorda} each get {@code (3+2)/2 = 2.5} points,
 * rather than either both getting the better position's score or both getting the worse one.
 * This is the standard convention for scoring a tie in a positional system.
 *
 * <p>Ties for the same final total score are broken only by the candidates' declared order
 * (the order {@code candidates} lists them in) — {@link java.util.List#sorted} is a stable
 * sort, so equal scores keep their relative declared-order position rather than being
 * reordered arbitrarily.
 */
public final class BordaMethod {

    private BordaMethod() {
    }

    public static BordaResult count(List<Candidate> candidates, List<Ballot> ballots, BordaScoringRule rule) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("there must be at least one candidate");
        }
        Map<Candidate, Fraction> scores = new LinkedHashMap<>();
        Map<Candidate, Integer> indexOf = new HashMap<>();
        for (Candidate candidate : candidates) {
            scores.put(candidate, Fraction.ZERO);
            indexOf.put(candidate, indexOf.size());
        }

        int totalCandidates = candidates.size();
        // How many votes each candidate has at each untied position, scored once at the end: per
        // ballot, exact fractions spent most of the count's time reducing by greatest common divisors.
        BigInteger[] votesAt = new BigInteger[totalCandidates * totalCandidates];
        for (Ballot ballot : ballots) {
            if (ballot.isBlank()) {
                continue;
            }
            int position = 0;
            for (Set<Candidate> ranked : ballot.ranking()) {
                if (ranked.size() == 1) {
                    Integer index = indexOf.get(ranked.iterator().next());
                    if (index != null) {
                        int at = index * totalCandidates + position;
                        votesAt[at] = votesAt[at] == null ? ballot.count() : votesAt[at].add(ballot.count());
                        position++;
                    }
                    continue;
                }
                // A withdrawn candidate is passed over: they take no position and score nothing.
                List<Candidate> rank = ranked.stream().filter(scores::containsKey).toList();
                if (rank.isEmpty()) {
                    continue;
                }
                int groupSize = rank.size();
                Fraction sumOfWeights = Fraction.ZERO;
                for (int offset = 0; offset < groupSize; offset++) {
                    sumOfWeights = sumOfWeights.add(rule.weight(position + offset, totalCandidates));
                }
                Fraction perCandidate = sumOfWeights.divide(Fraction.of(groupSize))
                        .multiply(Fraction.of(ballot.count()));
                for (Candidate candidate : rank) {
                    scores.merge(candidate, perCandidate, Fraction::add);
                }
                position += groupSize;
            }
        }
        for (Candidate candidate : candidates) {
            int index = indexOf.get(candidate);
            for (int position = 0; position < totalCandidates; position++) {
                BigInteger votes = votesAt[index * totalCandidates + position];
                if (votes != null) {
                    scores.merge(candidate, rule.weight(position, totalCandidates).multiply(Fraction.of(votes)),
                            Fraction::add);
                }
            }
        }

        List<Candidate> ranking = candidates.stream()
                .sorted(Comparator.comparing(scores::get).reversed())
                .toList();
        return new BordaResult(scores, ranking);
    }
}
