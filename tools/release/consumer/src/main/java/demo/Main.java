package demo;

import info.fkhodkov.rankedchoice.count.CountOptions;
import info.fkhodkov.rankedchoice.count.Election;
import info.fkhodkov.rankedchoice.count.ReportFormat;
import info.fkhodkov.rankedchoice.count.Rulesets;
import info.fkhodkov.rankedchoice.count.Tabulation;
import info.fkhodkov.rankedchoice.count.Tabulator;

public class Main {
    public static void main(String[] args) {
        String blt = String.join("\n", "4 2", "24 1 2 0", "16 1 3 2 0", "17 4 0", "0",
                "\"Andrea\"", "\"Brad\"", "\"Carter\"", "\"Delilah\"", "\"Example\"");
        Election election = Election.parse(blt, "blt");
        System.out.println("ruleset quota: " + Rulesets.load("scottish").quota());
        Tabulation tabulation = Tabulator.of(CountOptions.NONE.withRuleset("scottish"), null)
                .count(election, warning -> { });
        if (tabulation instanceof Tabulation.Stv stv) {
            System.out.println("elected: " + stv.result().electedInOrder().stream().map(c -> c.name()).toList());
        }
        System.out.println("json report: " + tabulation.report(ReportFormat.JSON).length() + " characters");
    }
}
