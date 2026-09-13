package info.fkhodkov.rankedchoice.schulze;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

/**
 * The pairwise preference matrix {@code d} that every Condorcet-family method (Schulze
 * included) starts from: {@code d[a][b]} is the number of ballots that rank {@code a} strictly
 * ahead of {@code b}, weighted by each ballot's {@link Ballot#count()}.
 *
 * <p>A candidate this project's {@link Ballot} model leaves off a ranking entirely is treated
 * as tied for last on that ballot, behind every candidate the voter did rank — the standard
 * convention (an unranked candidate is simply not preferred to anything the voter bothered to
 * rank) — so an unranked candidate contributes no comparisons against another unranked
 * candidate on the same ballot, exactly as a tie group within an explicit ranking wouldn't.
 * Unlike {@link info.fkhodkov.rankedchoice.stv.StvCounter} and {@link
 * info.fkhodkov.rankedchoice.stv.meek.MeekCounter}, this project's Condorcet methods accept
 * tied rankings directly — a pairwise comparison has nothing that needs a transfer ratio, so
 * there's no reason to reject them.
 */
public final class PairwiseMatrix {

    private PairwiseMatrix() {
    }

    public static Map<Candidate, Map<Candidate, BigInteger>> compute(List<Candidate> candidates, List<Ballot> ballots) {
        Map<Candidate, Map<Candidate, BigInteger>> d = new LinkedHashMap<>();
        for (Candidate a : candidates) {
            Map<Candidate, BigInteger> row = new LinkedHashMap<>();
            for (Candidate b : candidates) {
                if (!a.equals(b)) {
                    row.put(b, BigInteger.ZERO);
                }
            }
            d.put(a, row);
        }

        for (Ballot ballot : ballots) {
            if (ballot.isBlank()) {
                continue;
            }
            Map<Candidate, Integer> position = new HashMap<>();
            int pos = 0;
            for (Set<Candidate> rank : ballot.ranking()) {
                for (Candidate candidate : rank) {
                    position.put(candidate, pos);
                }
                pos++;
            }
            int unrankedPosition = pos;

            for (Candidate a : candidates) {
                int posA = position.getOrDefault(a, unrankedPosition);
                for (Candidate b : candidates) {
                    if (a.equals(b)) {
                        continue;
                    }
                    int posB = position.getOrDefault(b, unrankedPosition);
                    if (posA < posB) {
                        d.get(a).merge(b, ballot.count(), BigInteger::add);
                    }
                }
            }
        }
        return d;
    }
}
