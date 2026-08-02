package info.fkhodkov.rankedchoice.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsAllFieldsFromYaml() throws Exception {
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, """
                quota: hare
                transfer: wigm
                rounding: truncate:5
                tiebreak: previous-round,declared
                seats: 3
                format: csv
                """);

        ElectionConfig config = ConfigLoader.load(file);

        assertThat(config.quota()).isEqualTo("hare");
        assertThat(config.transfer()).isEqualTo("wigm");
        assertThat(config.rounding()).isEqualTo("truncate:5");
        assertThat(config.tiebreak()).isEqualTo("previous-round,declared");
        assertThat(config.seats()).isEqualTo(3);
        assertThat(config.format()).isEqualTo("csv");
    }

    @Test
    void fieldsLeftOutAreNull() throws Exception {
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, "quota: droop\n");

        ElectionConfig config = ConfigLoader.load(file);

        assertThat(config.quota()).isEqualTo("droop");
        assertThat(config.transfer()).isNull();
        assertThat(config.seats()).isNull();
    }

    @Test
    void loadsTheBundledScottishRuleset() {
        ElectionConfig config = ConfigLoader.loadResource("/rulesets/scottish.yaml");

        assertThat(config.quota()).isEqualTo("droop");
        assertThat(config.transfer()).isEqualTo("wigm");
        assertThat(config.rounding()).isEqualTo("truncate:5");
        assertThat(config.tiebreak()).isEqualTo("previous-round,declared");
    }

    @Test
    void missingBundledResourceThrows() {
        assertThatThrownBy(() -> ConfigLoader.loadResource("/rulesets/does-not-exist.yaml"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingFileThrows() {
        assertThatThrownBy(() -> ConfigLoader.load(tempDir.resolve("nope.yaml")))
                .isInstanceOf(UncheckedIOException.class);
    }
}
