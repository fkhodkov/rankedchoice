package info.fkhodkov.rankedchoice.count;

import java.util.List;
import info.fkhodkov.rankedchoice.borda.BordaReports;
import info.fkhodkov.rankedchoice.borda.BordaResult;
import info.fkhodkov.rankedchoice.borda.BordaTextReport;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.cpostv.CpoStvReports;
import info.fkhodkov.rankedchoice.cpostv.CpoStvResult;
import info.fkhodkov.rankedchoice.cpostv.CpoStvTextReport;
import info.fkhodkov.rankedchoice.qpq.QpqReports;
import info.fkhodkov.rankedchoice.qpq.QpqResult;
import info.fkhodkov.rankedchoice.qpq.QpqTextReport;
import info.fkhodkov.rankedchoice.schulze.SchulzeReports;
import info.fkhodkov.rankedchoice.schulze.SchulzeResult;
import info.fkhodkov.rankedchoice.schulze.SchulzeTextReport;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeProportionalReports;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeProportionalResult;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeStvReports;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeStvResult;
import info.fkhodkov.rankedchoice.schulzestv.SchulzeStvTextReport;
import info.fkhodkov.rankedchoice.stv.StvResult;
import info.fkhodkov.rankedchoice.stv.report.StvCsvReport;
import info.fkhodkov.rankedchoice.stv.report.StvHtmlReport;
import info.fkhodkov.rankedchoice.stv.report.StvJsonReport;
import info.fkhodkov.rankedchoice.stv.report.StvTextReport;
import info.fkhodkov.rankedchoice.stv.rounding.RoundingRule;

/**
 * A finished count: the method's own result, as data, which also renders its report in any
 * {@link ReportFormat}. There's one kind per kind of result, so a caller after the outcome
 * switches on it:
 *
 * <pre>{@code
 * switch (tabulation) {
 *     case Tabulation.Stv stv -> stv.result().electedInOrder();
 *     case Tabulation.CpoStv cpo -> cpo.result().winners();
 *     ...
 * }
 * }</pre>
 *
 * <p>The outcomes differ in kind, so there's no one list of winners: the STV family and QPQ
 * elect candidates in order; CPO-STV and Schulze STV choose whole committees, more than one
 * only in a genuine tie; Schulze, Borda and the Schulze proportional ranking rank every
 * candidate.
 */
public sealed interface Tabulation {

    /** The report, in {@code format}. */
    String report(ReportFormat format);

    /**
     * Classic STV, Meek's or Warren's method, or Cambridge STV ({@link Tabulator#method()} says
     * which), with the rounding its report shows figures to.
     */
    record Stv(StvResult result, RoundingRule rounding) implements Tabulation {
        @Override
        public String report(ReportFormat format) {
            return switch (format) {
                case CSV -> StvCsvReport.render(result, rounding);
                case JSON -> StvJsonReport.render(result, rounding);
                case HTML -> StvHtmlReport.render(result, rounding);
                case TEXT -> StvTextReport.render(result, rounding);
            };
        }
    }

    /** Woodall's Quota Preferential by Quotient. */
    record Qpq(QpqResult result) implements Tabulation {
        @Override
        public String report(ReportFormat format) {
            return switch (format) {
                case CSV -> QpqReports.csv(result);
                case JSON -> QpqReports.json(result);
                case HTML -> QpqReports.html(result);
                case TEXT -> QpqTextReport.render(result);
            };
        }
    }

    /** Tideman's CPO-STV. */
    record CpoStv(CpoStvResult result) implements Tabulation {
        @Override
        public String report(ReportFormat format) {
            return switch (format) {
                case CSV -> CpoStvReports.csv(result);
                case JSON -> CpoStvReports.json(result);
                case HTML -> CpoStvReports.html(result);
                case TEXT -> CpoStvTextReport.render(result);
            };
        }
    }

    /** Schulze STV. */
    record SchulzeStv(SchulzeStvResult result) implements Tabulation {
        @Override
        public String report(ReportFormat format) {
            return switch (format) {
                case CSV -> SchulzeStvReports.csv(result);
                case JSON -> SchulzeStvReports.json(result);
                case HTML -> SchulzeStvReports.html(result);
                case TEXT -> SchulzeStvTextReport.render(result);
            };
        }
    }

    /** The Schulze proportional ranking. */
    record SchulzeProportional(SchulzeProportionalResult result) implements Tabulation {
        @Override
        public String report(ReportFormat format) {
            return switch (format) {
                case CSV -> SchulzeProportionalReports.csv(result);
                case JSON -> SchulzeProportionalReports.json(result);
                case HTML -> SchulzeProportionalReports.html(result);
                case TEXT -> SchulzeProportionalReports.text(result);
            };
        }
    }

    /** Single-winner Schulze, with the candidates in the ballot file's order, which its report lists them in. */
    record Schulze(List<Candidate> candidates, SchulzeResult result) implements Tabulation {
        public Schulze {
            candidates = List.copyOf(candidates);
        }

        @Override
        public String report(ReportFormat format) {
            return switch (format) {
                case CSV -> SchulzeReports.csv(candidates, result);
                case JSON -> SchulzeReports.json(candidates, result);
                case HTML -> SchulzeReports.html(candidates, result);
                case TEXT -> SchulzeTextReport.render(candidates, result);
            };
        }
    }

    /** Borda, classic or Dowdall. */
    record Borda(BordaResult result) implements Tabulation {
        @Override
        public String report(ReportFormat format) {
            return switch (format) {
                case CSV -> BordaReports.csv(result);
                case JSON -> BordaReports.json(result);
                case HTML -> BordaReports.html(result);
                case TEXT -> BordaTextReport.render(result);
            };
        }
    }
}
