package info.fkhodkov.rankedchoice.stv.fixtures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * A real-election fixture test's results, in the shape the reference checkers write with {@code --results}, for
 * {@code tools/reference/results_page.py}: each contest, whether it checks out, how many rounds match out of how
 * many, whether the same candidates are elected, and a line saying so. Written only when the system property
 * {@code results.dir} names a directory:
 * {@code mvn test -Dtest=ActOfficialDistributionTest -Dresults.dir=tools/reference/results}.
 */
final class FixtureResults {

    private static final Map<String, List<Map<String, Object>>> RESULTS = new LinkedHashMap<>();

    private FixtureResults() {
    }

    /** Records one contest's result; {@code matched} and {@code rounds} are null where no rounds are compared. */
    static synchronized void record(String checker, String contest, boolean ok, Integer matched, Integer rounds,
            boolean winnersSame, String line) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contest", contest);
        result.put("ok", ok);
        result.put("matched", matched);
        result.put("rounds", rounds);
        result.put("winners_same", winnersSame);
        result.put("line", line);
        RESULTS.computeIfAbsent(checker, name -> new ArrayList<>()).add(result);
    }

    /** Writes a checker's results to {@code results.dir}/{@code checker}.json, if the property is set. */
    static synchronized void write(String checker) throws IOException {
        String directory = System.getProperty("results.dir");
        if (directory == null || !RESULTS.containsKey(checker)) {
            return;
        }
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("checker", checker);
        file.put("date", LocalDate.now().toString());
        file.put("contests", RESULTS.get(checker));
        String json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(file);
        Files.writeString(Path.of(directory, checker + ".json"), json + "\n");
    }
}
