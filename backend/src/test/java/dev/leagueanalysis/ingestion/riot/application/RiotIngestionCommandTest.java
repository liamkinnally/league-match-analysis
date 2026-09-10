package dev.leagueanalysis.ingestion.riot.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiotIngestionCommandTest {
    @Test
    void trimsAndAcceptsBoundaryValues() {
        assertThat(new RiotIngestionCommand(" Player ", " NA1 ", 1))
                .isEqualTo(new RiotIngestionCommand("Player", "NA1", 1));
        assertThat(new RiotIngestionCommand("Player", "NA1", 20).matchLimit()).isEqualTo(20);
    }

    @Test
    void rejectsBlankOrOversizedRiotIdParts() {
        assertThatThrownBy(() -> new RiotIngestionCommand(" ", "NA1", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_GAME_NAME");
        assertThatThrownBy(() -> new RiotIngestionCommand("x".repeat(65), "NA1", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_GAME_NAME");
        assertThatThrownBy(() -> new RiotIngestionCommand("Player", " ", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_TAG_LINE");
        assertThatThrownBy(() -> new RiotIngestionCommand("Player", "x".repeat(17), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_TAG_LINE");
    }

    @Test
    void rejectsMatchLimitOutsideOneThroughTwenty() {
        assertThatThrownBy(() -> new RiotIngestionCommand("Player", "NA1", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_MATCH_LIMIT");
        assertThatThrownBy(() -> new RiotIngestionCommand("Player", "NA1", 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_MATCH_LIMIT");
    }
}
