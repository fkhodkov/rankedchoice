import info.fkhodkov.rankedchoice.cambridge.CambridgeCounter;
import info.fkhodkov.rankedchoice.cambridge.CambridgeRuleset;
import info.fkhodkov.rankedchoice.core.ballot.BltParser;
import info.fkhodkov.rankedchoice.core.ballot.ParsedElection;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;
import info.fkhodkov.rankedchoice.stv.StvCounter;
import info.fkhodkov.rankedchoice.stv.config.StvRulesetParser;
import info.fkhodkov.rankedchoice.stv.meek.MeekCounter;
import info.fkhodkov.rankedchoice.stv.meek.MeekRuleset;
import info.fkhodkov.rankedchoice.stv.rounding.Exact;
import info.fkhodkov.rankedchoice.stv.tiebreak.DeclaredOrderTieBreak;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How much does ballot order change a Cambridge STV count? Cambridge's Cincinnati method moves
 * whole ballots chosen by their position in the count, so the same votes counted in a different
 * order can elect different people. For each election this counts it as filed and then in
 * SHUFFLES random orders of its individual ballots (a fixed seed, so runs repeat), and reports
 * every committee that came out and how often, next to the committees Scottish STV and Meek
 * elect (neither depends on order).
 *
 * <p>usage (from the project root, after mvn package -DskipTests):
 * <pre>
 *   java -cp cli/target/rankedchoice-cli-0.1.0-SNAPSHOT.jar tools/order-sensitivity/OrderProbe.java \
 *       [--json] [--save DIR] SHUFFLES FILE.blt...
 * </pre>
 * {@code --json} prints one JSON object per election instead of text. {@code --save DIR} writes,
 * for each committee seen, the first shuffled order that produced it as a BLT file (one ballot
 * per line, in that order), so it can be replayed: {@code --method cambridge --input ...}.
 */
public class OrderProbe {

    public static void main(String[] args) throws IOException {
        boolean json = false;
        Path save = null;
        int i = 0;
        for (; i < args.length && args[i].startsWith("--"); i++) {
            if (args[i].equals("--json")) {
                json = true;
            } else if (args[i].equals("--save")) {
                save = Path.of(args[++i]);
                Files.createDirectories(save);
            } else {
                throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (args.length - i < 2) {
            System.err.println("usage: OrderProbe [--json] [--save DIR] SHUFFLES FILE.blt...");
            System.exit(2);
        }
        int shuffles = Integer.parseInt(args[i]);
        for (String file : Arrays.copyOfRange(args, i + 1, args.length)) {
            probe(Path.of(file), shuffles, json, save);
        }
    }

    private static void probe(Path file, int shuffles, boolean json, Path save) throws IOException {
        ParsedElection election = BltParser.parse(file);
        List<Candidate> candidates = election.candidates();
        int seats = election.seats();
        CambridgeRuleset cambridge = new CambridgeRuleset(new DeclaredOrderTieBreak(candidates));
        String asFiled = committee(CambridgeCounter.count(candidates, election.ballots(), seats, cambridge).electedInOrder());

        List<Ballot> ballots = new ArrayList<>();
        for (Ballot ballot : election.ballots()) {
            Ballot one = new Ballot(ballot.ranking(), BigInteger.ONE);
            for (long k = 0; k < ballot.count().longValueExact(); k++) {
                ballots.add(one);
            }
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        Random random = new Random(12345);
        for (int s = 0; s < shuffles; s++) {
            Collections.shuffle(ballots, random);
            String elected = committee(CambridgeCounter.count(candidates, ballots, seats, cambridge).electedInOrder());
            if (!seen.containsKey(elected) && save != null) {
                writeBlt(save.resolve(stem(file) + "-order" + (seen.size() + 1) + ".blt"), candidates, seats, ballots);
            }
            seen.merge(elected, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> byCount = seen.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList();

        String scottish = committee(StvCounter.count(candidates, election.ballots(), seats,
                StvRulesetParser.parse("droop", "wigm", "truncate:5", "declared", false, "truncate", 0, candidates))
                .electedInOrder());
        String meek = committee(MeekCounter.count(candidates, election.ballots(), seats,
                new MeekRuleset(Exact.INSTANCE, new DeclaredOrderTieBreak(candidates))).electedInOrder());

        if (json) {
            String committees = byCount.stream().map(e -> "\"" + e.getKey() + "\": " + e.getValue())
                    .collect(Collectors.joining(", "));
            System.out.printf("{\"file\": \"%s\", \"seats\": %d, \"ballots\": %d, \"as_filed\": \"%s\", "
                    + "\"scottish\": \"%s\", \"meek\": \"%s\", \"shuffled\": {%s}}%n",
                    file, seats, ballots.size(), asFiled, scottish, meek, committees);
            return;
        }
        System.out.printf("%s: %d ballots, %d seats, %d shuffled orders%n", file, ballots.size(), seats, shuffles);
        for (int k = 0; k < byCount.size(); k++) {
            Map.Entry<String, Integer> entry = byCount.get(k);
            int filled = entry.getKey().isEmpty() ? 0 : entry.getKey().split(",").length;
            List<String> notes = new ArrayList<>();
            if (entry.getKey().equals(asFiled)) {
                notes.add("as filed");
            }
            if (entry.getKey().equals(scottish)) {
                notes.add("Scottish STV");
            }
            if (entry.getKey().equals(meek)) {
                notes.add("Meek");
            }
            if (filled < seats) {
                notes.add((seats - filled) + " seat(s) unfilled");
            }
            System.out.printf("  %4d  %s%s%n", entry.getValue(), entry.getKey(),
                    notes.isEmpty() ? "" : "   (" + String.join(", ", notes) + ")");
        }
        if (!seen.containsKey(scottish)) {
            System.out.printf("  never: %s (Scottish STV)%n", scottish);
        }
        if (!seen.containsKey(meek) && !meek.equals(scottish)) {
            System.out.printf("  never: %s (Meek)%n", meek);
        }
    }

    private static String committee(List<Candidate> elected) {
        return elected.stream().map(Candidate::name).sorted().collect(Collectors.joining(","));
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".blt") ? name.substring(0, name.length() - 4) : name;
    }

    private static void writeBlt(Path path, List<Candidate> candidates, int seats, List<Ballot> ballots)
            throws IOException {
        StringBuilder blt = new StringBuilder(candidates.size() + " " + seats + "\n");
        for (Ballot ballot : ballots) {
            blt.append("1");
            for (Set<Candidate> rank : ballot.ranking()) {
                blt.append(' ').append(candidates.indexOf(rank.iterator().next()) + 1);
            }
            blt.append(" 0\n");
        }
        blt.append("0\n");
        for (Candidate candidate : candidates) {
            blt.append('"').append(candidate.name()).append("\"\n");
        }
        blt.append("\"shuffled ballot order\"\n");
        Files.writeString(path, blt);
    }
}
