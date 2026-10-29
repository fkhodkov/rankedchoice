package info.fkhodkov.rankedchoice.core.model;

import java.util.HashMap;
import java.util.Map;

/**
 * A candidate on the ballot. {@code id} is the stable identity used to correlate a candidate
 * across parsing and counting (e.g. the numeric id in a .blt file); {@code name} is the
 * display name.
 */
public record Candidate(String id, String name) {

    public Candidate {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Candidate id must not be blank");
        }
        if (name == null || name.isBlank()) {
            name = id;
        }
    }

    public static Candidate of(String id) {
        return new Candidate(id, id);
    }

    /**
     * Refuses a candidate listed twice, which the counts would count twice, and two candidates of
     * the same name: the reports name candidates, and the JSON ones key their figures by name, so
     * one's would silently replace the other's.
     */
    public static void requireDistinctNames(Iterable<Candidate> candidates) {
        Map<String, Candidate> byName = new HashMap<>();
        for (Candidate candidate : candidates) {
            Candidate other = byName.putIfAbsent(candidate.name(), candidate);
            if (candidate.equals(other)) {
                throw new IllegalArgumentException("candidate " + candidate.id() + " (\"" + candidate.name()
                        + "\") is listed twice");
            }
            if (other != null) {
                throw new IllegalArgumentException("candidates " + other.id() + " and " + candidate.id()
                        + " are both named \"" + candidate.name() + "\"; the reports need every name to"
                        + " be different (add a middle initial, a party or a number)");
            }
        }
    }
}
