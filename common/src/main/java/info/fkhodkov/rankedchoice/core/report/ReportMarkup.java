package info.fkhodkov.rankedchoice.core.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The formatting every method's CSV, JSON and HTML reports share: RFC 4180 CSV quoting, HTML
 * escaping, a self-contained HTML page (inline CSS, no scripts or external assets), and one
 * JSON writer (indented, null fields left out).
 */
public final class ReportMarkup {

    private static final ObjectMapper JSON = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ReportMarkup() {
    }

    /** A CSV field, quoted only when it needs to be (a comma, quote, or line break). */
    public static String csvField(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** A complete HTML page around {@code body}, which must already be escaped. */
    public static String htmlPage(String title, String body) {
        return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n<title>" + escapeHtml(title)
                + "</title>\n<style>\n"
                + "body { font-family: sans-serif; }\n"
                + "table { border-collapse: collapse; margin-bottom: 1em; }\n"
                + "th, td { border: 1px solid #999; padding: 4px 10px; text-align: right; }\n"
                + "th:first-child, td:first-child { text-align: left; }\n"
                + "tr.acted { font-weight: bold; }\n"
                + "</style>\n</head>\n<body>\n" + body + "</body>\n</html>\n";
    }

    /**
     * Written as UTF-8 bytes, then made a string once: Jackson's own string writer holds the text
     * as two-byte chars and copies it whole (Schulze STV's 96 MB report for 24 candidates and 5
     * seats needed 640 MB of heap; now 384 MB). Through its char writer, since its byte writer
     * in 2.17 escapes a character past the BMP that the char writer writes as it is.
     */
    public static String json(Object report) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            JSON.writeValue(new OutputStreamWriter(bytes, StandardCharsets.UTF_8), report);
            return bytes.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize report to JSON", e);
        }
    }
}
