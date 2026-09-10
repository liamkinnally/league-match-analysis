package dev.leagueanalysis.ingestion.riot.domain;

import dev.leagueanalysis.evidence.domain.CanonicalEventKind;
import dev.leagueanalysis.evidence.domain.CoverageStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceDomainContractTest {
    private static final UUID CAPTURE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void evidenceBearingCoverageRequiresSourceProvenance() {
        for (var status : new CoverageStatus[] {
            CoverageStatus.OBSERVED,
            CoverageStatus.RECONSTRUCTED,
            CoverageStatus.ESTIMATED,
            CoverageStatus.LIMITED
        }) {
            assertThatThrownBy(() -> coverage(status, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("sourceCaptureId");
        }

        assertThatCode(() -> coverage(CoverageStatus.UNKNOWN, null)).doesNotThrowAnyException();
        assertThatCode(() -> coverage(CoverageStatus.UNAVAILABLE, null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankRequiredEvidenceIdentifiers() {
        var json = JsonNodeFactory.instance.objectNode();

        assertThatThrownBy(() -> new TeamFact(" ", 100, true, json))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParticipantFact(
                        "NA1_1", 1, "puuid", "Player", "NA1", 100, 1, "Annie", " ",
                        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, true, List.of(0, 0, 0, 0, 0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParticipantStateObservation(
                        UUID.randomUUID(), " ", 1, 0, null, null,
                        0, 0, 1, 0, 0, 0, CAPTURE_ID, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchEvent(
                        UUID.randomUUID(), "NA1_1", 0, 0, 0, " ",
                        CanonicalEventKind.OTHER, null, null, null, null,
                        json, CAPTURE_ID, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceCoverage(
                        UUID.randomUUID(), "NA1_1", SourceKind.MATCH_TIMELINE, " ",
                        CoverageStatus.OBSERVED, 0L, 0L, json, CAPTURE_ID, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIncompleteEndItemSlotSnapshots() {
        assertThatThrownBy(() -> participantWithEndItems(List.of(1056)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_END_ITEM_SLOTS");
    }

    @Test
    void rejectsNegativeEndItemIds() {
        assertThatThrownBy(() -> participantWithEndItems(List.of(1056, 2003, 0, 0, 0, 0, -1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_END_ITEM_SLOTS");
    }

    @Test
    void retainsAnImmutableEndItemSlotSnapshot() {
        var source = new ArrayList<>(List.of(1056, 2003, 0, 0, 0, 0, 3340));
        var participant = participantWithEndItems(source);

        source.set(0, 9999);

        assertThatThrownBy(() -> participant.endItemIds().add(9999))
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThat(participant.endItemIds())
                .containsExactly(1056, 2003, 0, 0, 0, 0, 3340);
    }

    private ParticipantFact participantWithEndItems(List<Integer> endItemIds) {
        return new ParticipantFact(
                "NA1_1", 1, "puuid", "Player", "NA1", 100, 1, "Annie", "MIDDLE",
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, true, endItemIds);
    }

    private EvidenceCoverage coverage(CoverageStatus status, UUID sourceCaptureId) {
        return new EvidenceCoverage(
                UUID.randomUUID(),
                "NA1_1",
                SourceKind.MATCH_TIMELINE,
                "participant_positions",
                status,
                null,
                null,
                JsonNodeFactory.instance.objectNode(),
                sourceCaptureId,
                "v1");
    }
}
