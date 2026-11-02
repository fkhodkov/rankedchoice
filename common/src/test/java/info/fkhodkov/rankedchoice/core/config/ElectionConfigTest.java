package info.fkhodkov.rankedchoice.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ElectionConfigTest {

    /** Every setting, as the YAML names it, in the config's order, with a value of its type. */
    private static final Map<String, Object> EVERY_SETTING = everySetting();

    @TempDir
    Path tempDir;

    private static Map<String, Object> everySetting() {
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Field field : ElectionConfig.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                Class<?> type = field.getType();
                int i = settings.size();
                settings.put(field.getName(), type == Integer.class ? (Object) (100 + i)
                        : type == Boolean.class ? (Object) (i % 2 == 0) : field.getName() + "-value");
            }
        }
        return settings;
    }

    private static ElectionConfig with(ElectionConfig config, String setting, Object value) throws Exception {
        String name = "with" + Character.toUpperCase(setting.charAt(0)) + setting.substring(1);
        Method with = Arrays.stream(ElectionConfig.class.getMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no " + name));
        return (ElectionConfig) with.invoke(config, value);
    }

    private static Object get(ElectionConfig config, String setting) throws Exception {
        return ElectionConfig.class.getMethod(setting).invoke(config);
    }

    @Test
    void hasTwentyOneSettings() {
        assertThat(EVERY_SETTING).hasSize(21);
    }

    @Test
    void eachWithSetsItsOwnSettingAlone() throws Exception {
        for (Map.Entry<String, Object> setting : EVERY_SETTING.entrySet()) {
            ElectionConfig config = with(ElectionConfig.NONE, setting.getKey(), setting.getValue());
            for (String other : EVERY_SETTING.keySet()) {
                assertThat(get(config, other)).as(setting.getKey() + " then " + other)
                        .isEqualTo(other.equals(setting.getKey()) ? setting.getValue() : null);
            }
        }
        assertThat(ElectionConfig.NONE).isEqualTo(ElectionConfig.NONE.withQuota("droop").withQuota(null));
    }

    @Test
    void aConfigMadeInCodeEqualsTheOneReadFromYaml() throws Exception {
        ElectionConfig made = ElectionConfig.NONE;
        StringBuilder yaml = new StringBuilder();
        for (Map.Entry<String, Object> setting : EVERY_SETTING.entrySet()) {
            made = with(made, setting.getKey(), setting.getValue());
            yaml.append(setting.getKey()).append(": ").append(setting.getValue()).append('\n');
        }
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, yaml);

        ElectionConfig read = ConfigLoader.load(file);

        assertThat(read).isEqualTo(made).hasSameHashCodeAs(made).hasToString(made.toString());
        assertThat(made).isNotEqualTo(with(made, "quota", "hare"));
        assertThat(made.toString()).startsWith("ElectionConfig[quota=quota-value, transfer=transfer-value, ");
    }

    @Test
    void jacksonWritesEverySettingInOrder() throws Exception {
        ElectionConfig made = ElectionConfig.NONE;
        for (Map.Entry<String, Object> setting : EVERY_SETTING.entrySet()) {
            made = with(made, setting.getKey(), setting.getValue());
        }
        Map<?, ?> written = new ObjectMapper().convertValue(made, Map.class);

        assertThat(written).isEqualTo(EVERY_SETTING);
        assertThat(List.copyOf(written.keySet())).isEqualTo(List.copyOf(EVERY_SETTING.keySet()));
    }

    @Test
    void anUnknownSettingNamesEveryOne() throws Exception {
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, "Quota: droop\n");

        assertThatThrownBy(() -> ConfigLoader.load(file))
                .hasMessageContaining("unknown setting 'Quota'; the settings are "
                        + EVERY_SETTING.keySet().stream().sorted().collect(Collectors.joining(", ")) + " (line");
    }
}
