package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import dev.leagueanalysis.evidence.domain.CanonicalEventKind;
import dev.leagueanalysis.evidence.domain.CoverageStatus;
import dev.leagueanalysis.ingestion.riot.domain.CapturedDocument;
import dev.leagueanalysis.ingestion.riot.domain.ParticipantStateObservation;
import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.SourceKind;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchV5DecoderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID DETAIL_CAPTURE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TIMELINE_CAPTURE_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");

    private final MatchV5Decoder decoder = new MatchV5Decoder();

    @Test
    void decodesActualFrameAndEventTimesAndPreservesUnknownEvents() {
        var result = decoder.decode(
                captured(SourceKind.MATCH_DETAIL, "match-detail-minimal.json", DETAIL_CAPTURE_ID),
                Optional.of(captured(SourceKind.MATCH_TIMELINE, "timeline-unknown-event.json", TIMELINE_CAPTURE_ID)));

        assertThat(result.match().gameVersion()).isEqualTo("16.17.810.4348");
        assertThat(result.match().detailSourceCaptureId()).isEqualTo(DETAIL_CAPTURE_ID);
        assertThat(result.match().timelineSourceCaptureId()).isEqualTo(TIMELINE_CAPTURE_ID);
        assertThat(result.observations()).extracting(ParticipantStateObservation::representedAtMs)
                .containsExactly(60000L, 60000L);

        var event = result.events().getFirst();
        assertThat(event.representedAtMs()).isEqualTo(59977L);
        assertThat(event.frameAtMs()).isEqualTo(60000L);
        assertThat(event.providerEventType()).isEqualTo("FUTURE_EVENT");
        assertThat(event.canonicalEventKind()).isEqualTo(CanonicalEventKind.OTHER);
        assertThat(event.eventPayload().has("optionalOmitted")).isFalse();
        assertThat(event.eventPayload().path("explicitNull").isNull()).isTrue();
    }

    @Test
    void decodesDetailWithoutTimelineAndMarksTemporalSignalsUnavailable() {
        var result = decoder.decode(
                captured(SourceKind.MATCH_DETAIL, "match-detail-minimal.json", DETAIL_CAPTURE_ID),
                Optional.empty());

        assertThat(result.teams()).hasSize(2);
        assertThat(result.participants()).hasSize(2);
        assertThat(result.participants().getFirst().endItemIds())
                .containsExactly(1056, 2003, 0, 0, 0, 0, 3340);
        assertThat(result.observations()).isEmpty();
        assertThat(result.events()).isEmpty();
        assertThat(result.coverage())
                .filteredOn(coverage -> coverage.sourceKind() == SourceKind.MATCH_TIMELINE)
                .allSatisfy(coverage -> {
                    assertThat(coverage.status()).isEqualTo(CoverageStatus.UNAVAILABLE);
                    assertThat(coverage.sourceCaptureId()).isNull();
                });
    }

    @Test
    void normalMatchesPreserveMissingRolesAsUnknown() throws IOException {
        var detail = fixture("match-detail-minimal.json").deepCopy();
        var participants = detail.path("info").path("participants");
        ((ObjectNode) participants.get(0)).put("teamPosition", "");
        ((ObjectNode) participants.get(1)).remove("teamPosition");
        var result = decoder.decode(captured(SourceKind.MATCH_DETAIL, detail, DETAIL_CAPTURE_ID), Optional.empty());
        assertThat(result.participants()).allSatisfy(p -> assertThat(p.teamPosition()).isEqualTo("UNKNOWN"));
    }

    @Test
    void rejectsMalformedRequiredMatchIdentityWithoutLeakingPayload() throws IOException {
        var malformed = fixture("match-detail-minimal.json").deepCopy();
        ((ObjectNode) malformed.path("metadata")).remove("matchId");
        var document = captured(SourceKind.MATCH_DETAIL, malformed, DETAIL_CAPTURE_ID);

        assertThatThrownBy(() -> decoder.decode(document, Optional.empty()))
                .isInstanceOf(RiotPayloadException.class)
                .hasMessage("INVALID_REQUIRED_FIELD")
                .hasMessageNotContaining("InventedPlayer");
    }

    @Test
    void rejectsAChronologicallyUnusableEmptyTimeline() throws IOException {
        var emptyTimeline = fixture("timeline-minimal.json").deepCopy();
        ((ObjectNode) emptyTimeline.path("info")).set("frames", JSON.createArrayNode());

        assertThatThrownBy(() -> decoder.decode(
                        captured(SourceKind.MATCH_DETAIL, "match-detail-minimal.json", DETAIL_CAPTURE_ID),
                        Optional.of(captured(SourceKind.MATCH_TIMELINE, emptyTimeline, TIMELINE_CAPTURE_ID))))
                .isInstanceOf(RiotPayloadException.class)
                .hasMessage("INVALID_REQUIRED_FIELD");
    }

    @Test
    void limitsCoverageWhenParticipantOrPositionSnapshotsAreIncomplete() throws IOException {
        var missingPosition = fixture("timeline-minimal.json").deepCopy();
        ((ObjectNode) missingPosition.path("info").path("frames").get(0)
                .path("participantFrames").path("2")).remove("position");
        var missingParticipant = fixture("timeline-minimal.json").deepCopy();
        ((ObjectNode) missingParticipant.path("info").path("frames").get(0)
                .path("participantFrames")).remove("2");

        var positionResult = decoder.decode(
                captured(SourceKind.MATCH_DETAIL, "match-detail-minimal.json", DETAIL_CAPTURE_ID),
                Optional.of(captured(SourceKind.MATCH_TIMELINE, missingPosition, TIMELINE_CAPTURE_ID)));
        var participantResult = decoder.decode(
                captured(SourceKind.MATCH_DETAIL, "match-detail-minimal.json", DETAIL_CAPTURE_ID),
                Optional.of(captured(SourceKind.MATCH_TIMELINE, missingParticipant, TIMELINE_CAPTURE_ID)));

        assertThat(coverage(positionResult, "participant_positions").status())
                .isEqualTo(CoverageStatus.LIMITED);
        assertThat(coverage(positionResult, "economy_snapshots").status())
                .isEqualTo(CoverageStatus.OBSERVED);
        assertThat(coverage(positionResult, "participant_positions").representedStartMs())
                .isEqualTo(60000L);
        assertThat(coverage(positionResult, "participant_positions").representedEndMs())
                .isEqualTo(60000L);
        assertThat(coverage(participantResult, "participant_positions").status())
                .isEqualTo(CoverageStatus.LIMITED);
        assertThat(coverage(participantResult, "economy_snapshots").status())
                .isEqualTo(CoverageStatus.LIMITED);
    }

    @Test
    void classifiesEveryPlannedKnownEventFamily() {
        var expected = Map.ofEntries(
                Map.entry("ITEM_PURCHASED", CanonicalEventKind.ITEM),
                Map.entry("ITEM_SOLD", CanonicalEventKind.ITEM),
                Map.entry("ITEM_DESTROYED", CanonicalEventKind.ITEM),
                Map.entry("ITEM_UNDO", CanonicalEventKind.ITEM),
                Map.entry("CHAMPION_KILL", CanonicalEventKind.CHAMPION_KILL),
                Map.entry("ELITE_MONSTER_KILL", CanonicalEventKind.OBJECTIVE),
                Map.entry("BUILDING_KILL", CanonicalEventKind.OBJECTIVE),
                Map.entry("TURRET_PLATE_DESTROYED", CanonicalEventKind.OBJECTIVE),
                Map.entry("DRAGON_SOUL_GIVEN", CanonicalEventKind.OBJECTIVE),
                Map.entry("OBJECTIVE_BOUNTY_PRESTART", CanonicalEventKind.OBJECTIVE),
                Map.entry("OBJECTIVE_BOUNTY_FINISH", CanonicalEventKind.OBJECTIVE),
                Map.entry("WARD_PLACED", CanonicalEventKind.WARD),
                Map.entry("WARD_KILL", CanonicalEventKind.WARD),
                Map.entry("SKILL_LEVEL_UP", CanonicalEventKind.PROGRESSION),
                Map.entry("LEVEL_UP", CanonicalEventKind.PROGRESSION));

        expected.forEach((type, kind) -> assertThat(decoder.classify(type)).isEqualTo(kind));
        assertThat(decoder.classify("FUTURE_EVENT")).isEqualTo(CanonicalEventKind.OTHER);
    }

    private dev.leagueanalysis.ingestion.riot.domain.EvidenceCoverage coverage(
            dev.leagueanalysis.ingestion.riot.domain.RiotMatchMaterialization materialization,
            String signal) {
        return materialization.coverage().stream()
                .filter(candidate -> candidate.signal().equals(signal))
                .findFirst()
                .orElseThrow();
    }

    private CapturedDocument captured(SourceKind kind, String fixture, UUID captureId) {
        try {
            return captured(kind, fixture(fixture), captureId);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private CapturedDocument captured(SourceKind kind, JsonNode payload, UUID captureId) {
        var provider = new ProviderDocument(
                kind,
                "NA1_9999999999",
                Instant.parse("2026-09-01T12:00:00Z"),
                200,
                "AMERICAS",
                "NA1",
                "16.17.810.4348",
                "a".repeat(64),
                payload.toString().length(),
                payload,
                JSON.createObjectNode(),
                "match-v5-v1",
                1);
        return new CapturedDocument(UUID.randomUUID(), captureId, provider);
    }

    private JsonNode fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/fixtures/riot/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing fixture: " + name);
            }
            return JSON.readTree(input);
        }
    }
}
