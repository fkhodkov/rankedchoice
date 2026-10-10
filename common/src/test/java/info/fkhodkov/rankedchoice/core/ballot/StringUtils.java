package info.fkhodkov.rankedchoice.core.ballot;

import java.util.stream.Stream;

public final class StringUtils {

    static Stream<String> makeLines(String content) {
        return Stream.of(content.split("\\R"));
    }

    private StringUtils() {}
}
