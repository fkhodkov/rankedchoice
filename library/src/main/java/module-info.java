/**
 * The counting methods, their reports and the bundled rulesets; {@code count.Tabulator} counts by any
 * of them.
 */
module info.fkhodkov.rankedchoice {
    requires transitive info.fkhodkov.rankedchoice.core;
    requires com.fasterxml.jackson.databind;

    exports info.fkhodkov.rankedchoice.borda;
    exports info.fkhodkov.rankedchoice.cambridge;
    exports info.fkhodkov.rankedchoice.count;
    exports info.fkhodkov.rankedchoice.cpostv;
    exports info.fkhodkov.rankedchoice.qpq;
    exports info.fkhodkov.rankedchoice.schulze;
    exports info.fkhodkov.rankedchoice.schulzestv;
    exports info.fkhodkov.rankedchoice.stv;
    exports info.fkhodkov.rankedchoice.stv.config;
    exports info.fkhodkov.rankedchoice.stv.meek;
    exports info.fkhodkov.rankedchoice.stv.quota;
    exports info.fkhodkov.rankedchoice.stv.report;
    exports info.fkhodkov.rankedchoice.stv.rounding;
    exports info.fkhodkov.rankedchoice.stv.tiebreak;
    exports info.fkhodkov.rankedchoice.stv.transfer;
}
