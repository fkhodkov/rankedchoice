/**
 * What every counting method shares: the election model, the ballot readers, configuration, exact
 * fractions and report markup.
 */
module info.fkhodkov.rankedchoice.core {
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.yaml;

    exports info.fkhodkov.rankedchoice.core.ballot;
    exports info.fkhodkov.rankedchoice.core.config;
    exports info.fkhodkov.rankedchoice.core.model;
    exports info.fkhodkov.rankedchoice.core.report;
    exports info.fkhodkov.rankedchoice.core.util;

    // ConfigLoader's Jackson builds an ElectionConfig through its private constructor.
    opens info.fkhodkov.rankedchoice.core.config to com.fasterxml.jackson.databind;
}
