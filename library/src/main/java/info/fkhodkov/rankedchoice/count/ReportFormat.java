package info.fkhodkov.rankedchoice.count;

import java.util.Locale;

/** The forms every method's report comes in. */
public enum ReportFormat {
    TEXT, CSV, JSON, HTML;

    /** The format named {@code text}, {@code csv}, {@code json} or {@code html}, in any case. */
    public static ReportFormat of(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "text" -> TEXT;
            case "csv" -> CSV;
            case "json" -> JSON;
            case "html" -> HTML;
            default -> throw new IllegalArgumentException("Unsupported report format '" + name.toLowerCase(Locale.ROOT)
                    + "'; expected 'text', 'csv', 'json', or 'html'");
        };
    }
}
