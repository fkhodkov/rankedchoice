package info.fkhodkov.rankedchoice.core.ballot;

import org.junit.jupiter.api.Test;
import info.fkhodkov.rankedchoice.core.model.Ballot;
import info.fkhodkov.rankedchoice.core.model.Candidate;

import java.math.BigInteger;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvBallotParserTest {

    @Test
    void parsesBasicBallotsInFirstSeenCandidateOrder() {
        String csv = """
                count,rank1,rank2,rank3
                42,Alice,Bob,
                7,Bob,Alice,Carol
                """;

        ParsedBallots parsed = CsvBallotParser.parse(csv);

        assertThat(parsed.candidates()).extracting(Candidate::name)
                .containsExactly("Alice", "Bob", "Carol");
        assertThat(parsed.ballots()).hasSize(2);

        Ballot first = parsed.ballots().get(0);
        assertThat(first.count()).isEqualTo(BigInteger.valueOf(42));
        assertThat(first.ranking()).containsExactly(
                Set.of(Candidate.of("Alice")), Set.of(Candidate.of("Bob")));

        Ballot second = parsed.ballots().get(1);
        assertThat(second.count()).isEqualTo(BigInteger.valueOf(7));
        assertThat(second.ranking()).containsExactly(
                Set.of(Candidate.of("Bob")), Set.of(Candidate.of("Alice")), Set.of(Candidate.of("Carol")));
    }

    @Test
    void parsesTieGroupsWithinACell() {
        String csv = """
                count,rank1,rank2
                3,Alice=Bob,Carol
                """;

        Ballot ballot = CsvBallotParser.parse(csv).ballots().get(0);
        assertThat(ballot.firstPreference())
                .containsExactlyInAnyOrder(Candidate.of("Alice"), Candidate.of("Bob"));
        assertThat(ballot.lastPreference()).containsExactly(Candidate.of("Carol"));
    }

    @Test
    void supportsQuotedCellsWithEmbeddedCommas() {
        String csv = """
                count,rank1
                1,"Smith, Jr."
                """;

        Ballot ballot = CsvBallotParser.parse(csv).ballots().get(0);
        assertThat(ballot.firstPreference()).containsExactly(Candidate.of("Smith, Jr."));
    }

    @Test
    void missingHeaderThrows() {
        assertThatThrownBy(() -> CsvBallotParser.parse("Alice,Bob\n1,Alice,Bob\n"))
                .isInstanceOf(BallotParseException.class);
    }

    @Test
    void gapInRankingThrows() {
        String csv = """
                count,rank1,rank2,rank3
                1,Alice,,Carol
                """;

        assertThatThrownBy(() -> CsvBallotParser.parse(csv))
                .isInstanceOf(BallotParseException.class)
                .hasMessageContaining("gap");
    }

    @Test
    void nonPositiveCountThrows() {
        String csv = """
                count,rank1
                0,Alice
                """;

        assertThatThrownBy(() -> CsvBallotParser.parse(csv))
                .isInstanceOf(BallotParseException.class);
    }

    @Test
    void emptyInputThrows() {
        assertThatThrownBy(() -> CsvBallotParser.parse("")).isInstanceOf(BallotParseException.class);
    }
}
