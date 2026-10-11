package info.fkhodkov.rankedchoice.server;

import java.time.Duration;

/**
 * What one demo server will take on: the largest request it reads, how many counts it runs at once
 * (a count asked for while they're all busy is turned away), and how long it waits for one.
 *
 * <p>A count can't be stopped partway, so one past its time keeps its place until it ends; only the
 * answer stops waiting. The cap on counts at once is what bounds the work.
 */
public record Limits(int maxRequestBytes, int maxConcurrentCounts, Duration countTimeout) {

    /** 5 MB requests, half the processors' counts at once (at least one), 30 seconds each. */
    public static final Limits DEFAULT = new Limits(5 * 1024 * 1024,
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2), Duration.ofSeconds(30));

    public Limits {
        if (maxRequestBytes < 1 || maxConcurrentCounts < 1 || countTimeout.isNegative() || countTimeout.isZero()) {
            throw new IllegalArgumentException("every limit must be positive");
        }
    }
}
