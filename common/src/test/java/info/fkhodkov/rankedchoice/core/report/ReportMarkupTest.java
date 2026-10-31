package info.fkhodkov.rankedchoice.core.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReportMarkupTest {

    record Named(String name, List<String> others, String missing) {
    }

    @Test
    void jsonKeepsEveryCharacterOfTheNamesAndLeavesNullsOut() {
        // Made as UTF-8 bytes first: accents, CJK and a character past the BMP come back whole.
        String json = ReportMarkup.json(new Named("Zoë \"Z\" Ångström", List.of("李明", "Ali 😀"), null));

        assertThat(json).isEqualTo("""
                {
                  "name" : "Zoë \\"Z\\" Ångström",
                  "others" : [ "李明", "Ali 😀" ]
                }""");
    }
}
