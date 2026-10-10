package info.fkhodkov.rankedchoice.core.ballot;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * A line of input paired with its 1-based line number in the original source, so error
 * messages can point at the actual file line even after blank/comment lines have been
 * filtered out of the stream.
 */
record NumberedLine(int number, String text) {

    /**
     * Numbers a (sequential) stream of raw lines by their position, 1-based, leaving out a UTF-8
     * byte order mark at the start of the first (spreadsheets' "CSV UTF-8" files have one).
     */
    static Stream<NumberedLine> number(Stream<String> lines) {
        AtomicInteger counter = new AtomicInteger(0);
        return lines.map(line -> {
            int number = counter.incrementAndGet();
            return new NumberedLine(number, number == 1 && line.startsWith(BYTE_ORDER_MARK) ? line.substring(1) : line);
        });
    }

    private static final String BYTE_ORDER_MARK = "\uFEFF";
}
