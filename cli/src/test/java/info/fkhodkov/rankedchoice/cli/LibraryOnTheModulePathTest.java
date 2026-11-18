package info.fkhodkov.rankedchoice.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import info.fkhodkov.rankedchoice.core.config.ConfigLoader;
import info.fkhodkov.rankedchoice.count.ReportFormat;
import info.fkhodkov.rankedchoice.count.Rulesets;
import info.fkhodkov.rankedchoice.count.Tabulator;

/**
 * The library's jars as a modular program uses them: a consumer module of its own, compiled and run
 * with {@code common}'s and {@code library}'s jars and their dependencies on the module path, where
 * each jar is a module of its own and a class finds only its own module's resources (the bundled
 * rulesets once weren't found there). It needs the packaged jars, so it runs under {@code mvn verify}
 * (as the pre-commit hook does), not {@code mvn test}.
 */
class LibraryOnTheModulePathTest {

    private static final String MODULE_INFO = """
            module consumer {
                requires info.fkhodkov.rankedchoice;
            }
            """;

    private static final String MAIN = """
            package consumer;

            import java.io.ByteArrayInputStream;
            import java.nio.charset.StandardCharsets;
            import info.fkhodkov.rankedchoice.core.config.ConfigLoader;
            import info.fkhodkov.rankedchoice.core.config.ElectionConfig;
            import info.fkhodkov.rankedchoice.count.CountOptions;
            import info.fkhodkov.rankedchoice.count.Election;
            import info.fkhodkov.rankedchoice.count.ReportFormat;
            import info.fkhodkov.rankedchoice.count.Rulesets;
            import info.fkhodkov.rankedchoice.count.Tabulation;
            import info.fkhodkov.rankedchoice.count.Tabulator;

            public class Main {
                public static void main(String[] args) {
                    System.out.println("modules " + Tabulator.class.getModule().getName() + " "
                            + ElectionConfig.class.getModule().getName());
                    for (Rulesets.Ruleset ruleset : Rulesets.ALL) {
                        System.out.println(ruleset.name() + " " + Rulesets.load(ruleset.name()).quota());
                    }
                    byte[] yaml = "quota: hare\\n".getBytes(StandardCharsets.UTF_8);
                    ElectionConfig read = ConfigLoader.read(new ByteArrayInputStream(yaml), "inline");
                    System.out.println("read " + read.quota());
                    String blt = String.join("\\n", "4 2", "24 1 2 0", "16 1 3 2 0", "17 4 0", "0",
                            "\\"Andrea\\"", "\\"Brad\\"", "\\"Carter\\"", "\\"Delilah\\"", "\\"Example\\"");
                    Election election = Election.parse(blt, "blt");
                    Tabulation tabulation = Tabulator.of(CountOptions.NONE.withRuleset("scottish"), null)
                            .count(election, warning -> { });
                    if (tabulation instanceof Tabulation.Stv stv) {
                        var names = stv.result().electedInOrder().stream().map(c -> c.name()).toList();
                        System.out.println("elected " + names);
                    }
                    for (Tabulator.Method method : Tabulator.METHODS) {
                        // Cambridge STV needs 50 ballots a seat: these 57 fill one.
                        CountOptions options = CountOptions.NONE.withMethod(method.name())
                                .withSeats(method.name().equals("cambridge") ? 1 : null);
                        Tabulation counted = Tabulator.of(options, null).count(election, warning -> { });
                        for (ReportFormat format : ReportFormat.values()) {
                            String report = counted.report(format);
                            System.out.println(method.name() + " " + format + " " + report.contains("Andrea"));
                        }
                    }
                }
            }
            """;

    @TempDir
    Path dir;

    private static Path jarOf(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    @Test
    void countsByABundledRulesetFromAModule() throws Exception {
        Path common = jarOf(ConfigLoader.class);
        Path library = jarOf(Rulesets.class);
        Assumptions.assumeTrue(common.toString().endsWith(".jar") && library.toString().endsWith(".jar"),
                "the library's packaged jars, which mvn verify builds before this test, not mvn test");
        String modulePath = Stream.of(ConfigLoader.class, Rulesets.class, ObjectMapper.class, JsonFactory.class,
                        JsonProperty.class, YAMLFactory.class, Yaml.class)
                .map(type -> {
                    try {
                        return jarOf(type).toString();
                    } catch (URISyntaxException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .distinct().collect(Collectors.joining(File.pathSeparator));

        Path source = dir.resolve("src");
        Files.createDirectories(source.resolve("consumer"));
        Files.writeString(source.resolve("module-info.java"), MODULE_INFO);
        Files.writeString(source.resolve("consumer/Main.java"), MAIN);
        Path classes = dir.resolve("classes");
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--module-path", modulePath,
                "-d", classes.toString(), source.resolve("module-info.java").toString(),
                source.resolve("consumer/Main.java").toString());
        assertThat(compiled).as("the consumer compiled").isZero();

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // Its output to a file, not read as it runs: reading would wait for a hung consumer, past any timeout.
        Path log = dir.resolve("consumer.log");
        Process program = new ProcessBuilder(java, "--module-path", modulePath + File.pathSeparator + classes,
                "-m", "consumer/consumer.Main").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean ended = program.waitFor(60, TimeUnit.SECONDS);
        if (!ended) {
            program.destroyForcibly().waitFor();
        }
        String output = Files.readString(log);
        assertThat(ended).as("the consumer ended within a minute: " + output).isTrue();

        assertThat(program.exitValue()).as(output).isZero();
        List<String> lines = output.lines().toList();
        assertThat(lines.getFirst()).isEqualTo("modules info.fkhodkov.rankedchoice info.fkhodkov.rankedchoice.core");
        assertThat(lines).contains("scottish droop", "irv hare", "read hare", "elected [Andrea, Brad]",
                        "schulze-stv JSON true", "borda HTML true")
                .hasSize(3 + Rulesets.ALL.size() + Tabulator.METHODS.size() * ReportFormat.values().length)
                .noneMatch(line -> line.endsWith(" false"));
    }
}
