package info.fkhodkov.rankedchoice.server;

import java.time.Duration;

/**
 * What one demo server will take on: the largest request it reads, how many counts it runs at once
 * (a count asked for while they're all busy is turned away), how long it waits for one, and how
 * many ballots a Cambridge STV count may hold.
 *
 * <p>A count past its time is interrupted, and stops at its next check (within a fraction of a
 * second); it keeps its place until then, so the cap on counts at once still bounds the work.
 *
 * <p>Cambridge STV keeps every ballot one by one, in order, where the other methods keep each
 * distinct ranking once with its count; so a ballot's count, a few bytes of the request, can
 * stand for as many ballots as it likes, and a count of a billion would take gigabytes. A
 * million ballots count in a 32 MB heap in under a second.
 */
public record Limits(int maxRequestBytes, int maxConcurrentCounts, Duration countTimeout, int maxCambridgeBallots) {

    /**
     * 5 MB requests, half the processors' counts at once (at least one), 30 seconds each, and a
     * million ballots for Cambridge STV.
     */
    public static final Limits DEFAULT = new Limits(5 * 1024 * 1024,
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2), Duration.ofSeconds(30), 1_000_000);

    public Limits {
        if (maxRequestBytes < 1 || maxConcurrentCounts < 1 || countTimeout.isNegative() || countTimeout.isZero()
                || maxCambridgeBallots < 1) {
            throw new IllegalArgumentException("every limit must be positive");
        }
    }
}
