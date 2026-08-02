package info.fkhodkov.rankedchoice.core.ballot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static info.fkhodkov.rankedchoice.core.ballot.StringUtils.makeLines;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

class BltParserTest {

    private static final String SAMPLE = """
            4 2
            # a withdrawn-candidates line, candidate 3 stood down
            -3

            5 1 2=3 4 0
            2 2 1 0
            3 4 0
            1 0
            0
            "Alice"
            "Bob"
            "Carol"
            "Dave"
            "Sample Election"
            """;

    @Test
    void parsesHeaderAndCandidates() {
        ParsedElection election = BltParser.parse(makeLines(SAMPLE));

        assertThat(election.seats()).isEqualTo(2);
        assertThat(election.title()).isEqualTo("Sample Election");
        assertThat(election.candidates()).extracting(Candidate::id, Candidate::name)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1", "Alice"),
                        org.assertj.core.groups.Tuple.tuple("2", "Bob"),
                        org.assertj.core.groups.Tuple.tuple("3", "Carol"),
                        org.assertj.core.groups.Tuple.tuple("4", "Dave"));
    }

    @Test
    void parsesWithdrawnCandidates() {
        ParsedElection election = BltParser.parse(makeLines(SAMPLE));
        assertThat(election.withdrawnCandidates()).containsExactly(new Candidate("3", "Carol"));
    }

    @Test
    void parsesBallotsWithTieGroupsAndCounts() {
        ParsedElection election = BltParser.parse(makeLines(SAMPLE));
        Candidate alice = new Candidate("1", "Alice");
        Candidate bob = new Candidate("2", "Bob");
        Candidate carol = new Candidate("3", "Carol");
        Candidate dave = new Candidate("4", "Dave");

        assertThat(election.ballots()).hasSize(4);

        Ballot first = election.ballots().getFirst();
        assertThat(first.count()).isEqualTo(BigInteger.valueOf(5));
        assertThat(first.ranking()).containsExactly(Set.of(alice), Set.of(bob, carol), Set.of(dave));

        Ballot second = election.ballots().get(1);
        assertThat(second.count()).isEqualTo(BigInteger.TWO);
        assertThat(second.ranking()).containsExactly(Set.of(bob), Set.of(alice));

        Ballot last = election.ballots().get(3);
        assertThat(last.count()).isEqualTo(BigInteger.ONE);
        assertThat(last.isBlank()).isTrue();
    }

    @Test
    void ignoresBlankAndCommentLinesAnywhere() {
        String withExtraComments = "# a full election\n" + SAMPLE + "\n# trailing comment\n";
        assertThat(BltParser.parse(makeLines(withExtraComments)).ballots()).hasSize(4);
    }

    @Test
    void missingHeaderThrows() {
        var lines = makeLines("\"only a title\"");
        assertThatThrownBy(() -> BltParser.parse(lines)).isInstanceOf(BallotParseException.class);
    }

    @Test
    void ballotLineWithoutZeroTerminatorThrows() {
        String bad = """
                2 1
                1 1 2
                0
                "A"
                "B"
                """;
        var lines = makeLines(bad);
        assertThatThrownBy(() -> BltParser.parse(lines))
                .isInstanceOf(BallotParseException.class)
                .hasMessageContaining("0");
    }

    @Test
    void errorMessageReportsOriginalFileLineNumberNotSignificantLineIndex() {
        // Blank line and a comment precede the bad line, so its real line number (4) differs
        // from its position among significant lines (2).
        String bad = """
                2 1

                # a comment
                1 1 2
                0
                "A"
                "B"
                """;
        var lines = makeLines(bad);
        assertThatThrownBy(() -> BltParser.parse(lines))
                .isInstanceOf(BallotParseException.class)
                .hasMessageContaining("Line 4:");
    }

    @Test
    void outOfRangeCandidateIdThrows() {
        String bad = """
                2 1
                1 5 0
                0
                "A"
                "B"
                """;
        var lines = makeLines(bad);
        assertThatThrownBy(() -> BltParser.parse(lines))
                .isInstanceOf(BallotParseException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void outOfRangeCandidateIdReportsOriginalFileLineNumber() {
        String bad = """
                2 1


                1 5 0
                0
                "A"
                "B"
                """;
        var lines = makeLines(bad);
        assertThatThrownBy(() -> BltParser.parse(lines))
                .isInstanceOf(BallotParseException.class)
                .hasMessageContaining("Line 4:")
                .hasMessageContaining("out of range");
    }

    @Test
    void unquotedCandidateNameThrows() {
        String bad = """
                1 1
                1 1 0
                0
                A
                """;
        var lines = makeLines(bad);
        assertThatThrownBy(() -> BltParser.parse(lines))
                .isInstanceOf(BallotParseException.class);
    }

    @Test
    void titleIsOptional() {
        String noTitle = """
                1 1
                3 1 0
                0
                "Only Candidate"
                """;
        ParsedElection election = BltParser.parse(makeLines(noTitle));
        assertThat(election.title()).isNull();
        assertThat(election.ballots()).containsExactly(
                new Ballot(List.of(Set.of(new Candidate("1", "Only Candidate"))), BigInteger.valueOf(3)));
    }
}
