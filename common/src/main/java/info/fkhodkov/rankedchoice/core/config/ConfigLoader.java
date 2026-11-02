package info.fkhodkov.rankedchoice.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Loads an {@link ElectionConfig} from a YAML file or stream; the bundled rulesets are
 * {@code count.Rulesets}'s, in the library.
 */
public final class ConfigLoader {

    /**
     * A whole-number setting given as 2.5 is refused, not cut to 2; a true/false one given as 1, not taken as
     * true. The stream read is left open, as {@link #read} promises: Jackson would otherwise close it.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);

    static {
        MAPPER.coercionConfigFor(LogicalType.Boolean).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
    }

    private ConfigLoader() {
    }

    public static ElectionConfig load(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return read(in, "Config file " + path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config file: " + path, e);
        }
    }

    /**
     * Reads a config's YAML from a stream, which it leaves open. Not YAML, or not a config, is
     * refused with what and where, {@code name} saying which config (e.g. "Config file x.yaml").
     */
    public static ElectionConfig read(InputStream in, String name) {
        try {
            return MAPPER.readValue(in, ElectionConfig.class);
        } catch (JsonProcessingException e) {
            // Not YAML, or not a config: say what and where, as Jackson does, without its long trace of the source.
            throw new IllegalArgumentException(name + " isn't a valid config: " + describe(e) + location(e), e);
        } catch (IOException e) {
            throw new UncheckedIOException(name + " couldn't be read", e);
        }
    }

    /**
     * What's wrong, in the config's own terms where it's a setting the file got wrong (Jackson's
     * own words name Java types); a YAML syntax error is left as the YAML parser puts it.
     */
    private static String describe(JsonProcessingException e) {
        if (e instanceof UnrecognizedPropertyException unknown) {
            return "unknown setting '" + unknown.getPropertyName() + "'; the settings are "
                    + unknown.getKnownPropertyIds().stream().map(Object::toString).sorted()
                            .collect(Collectors.joining(", "));
        }
        if (e instanceof MismatchedInputException mismatch) {
            if (mismatch.getPath().isEmpty()) {
                return e.getOriginalMessage().startsWith("No content")
                        ? "it's empty" : "it should be a list of 'setting: value' lines";
            }
            String setting = mismatch.getPath().getLast().getFieldName();
            Class<?> type = mismatch.getTargetType();
            String expected = type == Integer.class || type == int.class ? "a whole number"
                    : type == Boolean.class || type == boolean.class ? "true or false"
                    : type == String.class ? "a single value" : "a " + type.getSimpleName();
            String got = e instanceof InvalidFormatException invalid ? ", got '" + invalid.getValue() + "'" : "";
            return "'" + setting + "' should be " + expected + got;
        }
        return e.getOriginalMessage();
    }

    private static String location(JsonProcessingException e) {
        return e.getLocation() == null ? ""
                : " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
    }
}
