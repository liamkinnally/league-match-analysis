package dev.leagueanalysis.ingestion.riot.application;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HistoryCommandTest {
    @Test void supportsBoundedPagesAcrossSummonersRiftQueuesAndPreservesLocalDefaults() {
        assertThat(new RiotIngestionCommand("Player", "NA1", 5).queueId()).isEqualTo(420);
        for (int queue : new int[] {0, 400, 420, 430, 440, 450, 480, 490}) {
            var page = new RiotIngestionCommand(" Player ", "NA1", 20, queue, 20, 1780000000L, UUID.randomUUID());
            assertThat(page.gameName()).isEqualTo("Player");
            assertThat(page.start()).isEqualTo(20);
        }
        assertThatThrownBy(() -> new RiotIngestionCommand("Player", "NA1", 20, 1700, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiotIngestionCommand("Player", "NA1", 20, 420, -1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiotIngestionCommand("Player", "NA1", 21))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
