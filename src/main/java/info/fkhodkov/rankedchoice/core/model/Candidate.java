package info.fkhodkov.rankedchoice.core.model;

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
}
