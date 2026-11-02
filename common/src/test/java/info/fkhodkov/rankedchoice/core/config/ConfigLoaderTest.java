package info.fkhodkov.rankedchoice.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
    void readsAStreamNamingItInErrors() {
        ElectionConfig config = ConfigLoader.read(
                new ByteArrayInputStream("quota: hare\n".getBytes(StandardCharsets.UTF_8)), "Inline config");

        assertThat(config).isEqualTo(ElectionConfig.NONE.withQuota("hare"));
        assertThatThrownBy(() -> ConfigLoader.read(
                new ByteArrayInputStream("seats: two\n".getBytes(StandardCharsets.UTF_8)), "Inline config"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Inline config isn't a valid config: 'seats' should be a whole number, got 'two'"
                        + " (line 1, column 8)");
    }

    /** The caller's stream stays open, read or refused: it's theirs to close. */
    @Test
    void leavesTheStreamOpen() {
        for (String yaml : new String[] {"quota: hare\n", "seats: two\n"}) {
            boolean[] closed = {false};
            InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public void close() {
                    closed[0] = true;
                }
            };
            try {
                ConfigLoader.read(in, "Inline config");
            } catch (IllegalArgumentException refused) {
                assertThat(yaml).startsWith("seats");
            }
            assertThat(closed[0]).as(yaml).isFalse();
        }
    }

    @Test
    void missingFileThrows() {
        assertThatThrownBy(() -> ConfigLoader.load(tempDir.resolve("nope.yaml")))
                .isInstanceOf(UncheckedIOException.class);
    }

    /** A setting the file gets wrong is described in the config's own terms, not Jackson's Java types. */
    @Test
    void saysWhatSettingIsWrongInTheConfigsOwnTerms() throws Exception {
        assertRefused("", "isn't a valid config: it's empty");
        assertRefused("# only a comment\n", "isn't a valid config: it's empty");
        assertRefused("- quota\n- droop\n", "it should be a list of 'setting: value' lines");
        assertRefused("seats: two\n", "'seats' should be a whole number, got 'two'");
        assertRefused("seats: 2.5\n", "'seats' should be a whole number, got '2.5'");
        assertRefused("quota: [droop]\n", "'quota' should be a single value");
        assertRefused("batchExclusion: 1\n", "'batchExclusion' should be true or false, got '1'");
        assertRefused("Quota: droop\n", "unknown setting 'Quota'; the settings are batchExclusion, ");
    }

    private void assertRefused(String yaml, String message) throws Exception {
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, yaml);
        assertThatThrownBy(() -> ConfigLoader.load(file)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message)
                .hasMessageNotContaining("java.lang").hasMessageNotContaining("info.fkhodkov");
    }
}
