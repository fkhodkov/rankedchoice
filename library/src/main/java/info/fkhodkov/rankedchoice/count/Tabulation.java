package info.fkhodkov.rankedchoice.count;

import java.util.function.Function;

/** A finished count, which renders its report in any {@link ReportFormat}. */
public final class Tabulation {

    private final Function<ReportFormat, String> renderer;

    Tabulation(Function<ReportFormat, String> renderer) {
        this.renderer = renderer;
    }

    public String report(ReportFormat format) {
        return renderer.apply(format);
    }
}
