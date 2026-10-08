package info.fkhodkov.rankedchoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every fixture directory under {@code testdata/} has a README, and the README accounts for
 * every file in it (AGENTS.md: each says where its ballots and official figures come from and how
 * they were converted). A file counts as accounted for when the README names it, names it without
 * its extensions ({@code nauru-2019-anabar} for {@code nauru-2019-anabar.blt}), or has a backticked
 * pattern that matches it, a {@code <placeholder>} standing for any name ({@code <contest>.blt},
 * {@code act-2020-<electorate>.official.txt}; a {@code .gz} on the file is allowed for). Files in
 * subdirectories count against the directory's own README.
 */
class FixtureReadmeTest {

    private static final Path TESTDATA = Path.of("src/test/resources/testdata");

    private static final Pattern BACKTICKED_PATTERN = Pattern.compile("`([^`\\s]*<[^>`]+>[^`\\s]*)`");

    @Test
    void everyFixtureDirectoryHasAReadmeThatAccountsForEveryFile() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> directories = Files.list(TESTDATA)) {
            for (Path directory : directories.filter(Files::isDirectory).sorted().toList()) {
                Path readme = directory.resolve("README.md");
                if (!Files.exists(readme)) {
                    missing.add(TESTDATA.relativize(directory) + "/ has no README.md");
                    continue;
                }
                missing.addAll(unaccounted(directory, Files.readString(readme)));
            }
        }
        assertThat(missing).isEmpty();
    }

    private static List<String> unaccounted(Path directory, String readme) throws IOException {
        List<Pattern> patterns = new ArrayList<>();
        Matcher matcher = BACKTICKED_PATTERN.matcher(readme);
        while (matcher.find()) {
            String regex = Pattern.quote(matcher.group(1)).replaceAll("<[^>]+>", "\\\\E[^/]+\\\\Q");
            patterns.add(Pattern.compile("(.*/)?" + regex + "(\\.gz)?"));
        }
        List<String> unaccounted = new ArrayList<>();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString();
                String relative = directory.relativize(file).toString().replace('\\', '/');
                String stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
                boolean named = name.equals("README.md") || readme.contains(name) || readme.contains(stem)
                        || readme.contains(relative);
                boolean matched = patterns.stream()
                        .anyMatch(pattern -> pattern.matcher(relative).matches() || pattern.matcher(name).matches());
                if (!named && !matched) {
                    unaccounted.add(TESTDATA.relativize(file) + " isn't in its directory's README.md");
                }
            }
        }
        return unaccounted;
    }
}
