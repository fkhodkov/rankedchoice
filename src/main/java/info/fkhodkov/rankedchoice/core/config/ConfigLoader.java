package info.fkhodkov.rankedchoice.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** Loads an {@link ElectionConfig} from a YAML file, or from a bundled classpath resource. */
public final class ConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    private ConfigLoader() {
    }

    public static ElectionConfig load(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return read(in);
        } catch (JsonProcessingException e) {
            // Not YAML, or not a config: say what and where, as Jackson does, without its long trace of the source.
            throw new IllegalArgumentException("Config file " + path + " isn't a valid config: "
                    + e.getOriginalMessage() + location(e), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config file: " + path, e);
        }
    }

    private static String location(JsonProcessingException e) {
        return e.getLocation() == null ? ""
                : " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
    }

    /** Loads a bundled ruleset preset, e.g. {@code loadResource("/rulesets/scottish.yaml")}. */
    public static ElectionConfig loadResource(String resourcePath) {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("No such bundled ruleset resource: " + resourcePath);
            }
            return read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read bundled ruleset resource: " + resourcePath, e);
        }
    }

    private static ElectionConfig read(InputStream in) throws IOException {
        return MAPPER.readValue(in, ElectionConfig.class);
    }
}
