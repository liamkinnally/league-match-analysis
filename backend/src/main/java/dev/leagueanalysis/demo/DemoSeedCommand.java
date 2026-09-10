package dev.leagueanalysis.demo;

import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.application.IngestionItemStatus;
import dev.leagueanalysis.ingestion.riot.application.IngestionRunStatus;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionCommand;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionStore;
import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.SourceKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class DemoSeedCommand implements ApplicationRunner {
    public static final String MATCH_ID = "NA1_7000000001";
    public static final int FOCUS_PARTICIPANT_ID = 6;
    public static final int COMPARE_PARTICIPANT_ID = 1;
    public static final String FIXTURE_VERSION = "demo-match-v1";

    private static final Instant CAPTURED_AT = Instant.parse("2026-09-08T18:00:00Z");

    private final RiotIngestionStore store;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MatchV5Decoder decoder = new MatchV5Decoder();

    public DemoSeedCommand(RiotIngestionStore store, JdbcTemplate jdbc, ObjectMapper json) {
        this.store = store;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("seed-demo")) {
            seed();
        }
    }

    public SeedResult seed() {
        var detail = fixture(SourceKind.MATCH_DETAIL, "match.json");
        var timeline = fixture(SourceKind.MATCH_TIMELINE, "timeline.json");
        var existing = existingFixture();
        if (existing.isPresent()) {
            if (existing.orElseThrow().matches(detail.bodySha256(), timeline.bodySha256())) {
                return SeedResult.ALREADY_PRESENT;
            }
            throw new IllegalStateException("DEMO_MATCH_ID_CONFLICT");
        }

        var runId = store.startRun(
                new RiotIngestionCommand("Invented Demo", "DEMO", 1), CAPTURED_AT);
        store.addItems(runId, java.util.List.of(MATCH_ID));
        store.markItemRunning(runId, MATCH_ID, CAPTURED_AT);
        try {
            var detailCapture = store.saveCapture(runId, detail);
            var timelineCapture = store.saveCapture(runId, timeline);
            var materialization = decoder.decode(detailCapture, Optional.of(timelineCapture));
            store.materialize(runId, MATCH_ID, materialization);
            store.markItemTerminal(
                    runId, MATCH_ID, IngestionItemStatus.COMPLETE, null, null, CAPTURED_AT);
            store.finishRun(runId, IngestionRunStatus.COMPLETE, null, null, CAPTURED_AT);
            return SeedResult.CREATED;
        } catch (RuntimeException exception) {
            store.markItemTerminal(
                    runId,
                    MATCH_ID,
                    IngestionItemStatus.FAILED,
                    "DEMO_SEED_FAILED",
                    "The demo match could not be seeded.",
                    CAPTURED_AT);
            store.finishRun(
                    runId,
                    IngestionRunStatus.FAILED,
                    "DEMO_SEED_FAILED",
                    "The demo match could not be seeded.",
                    CAPTURED_AT);
            throw exception;
        }
    }

    public Optional<DemoMatch> locate() {
        return existingFixture()
                .filter(ExistingFixture::hasMarker)
                .map(ignored -> new DemoMatch(
                        MATCH_ID, FOCUS_PARTICIPANT_ID, COMPARE_PARTICIPANT_ID, true));
    }

    private Optional<ExistingFixture> existingFixture() {
        return jdbc.query("""
                select detail_payload.body_sha256 as detail_sha,
                       timeline_payload.body_sha256 as timeline_sha,
                       detail_capture.response_metadata::text as detail_metadata,
                       timeline_capture.response_metadata::text as timeline_metadata
                from league_analysis.riot_match rm
                join league_analysis.source_capture detail_capture
                  on detail_capture.id = rm.detail_source_capture_id
                join league_analysis.source_payload detail_payload
                  on detail_payload.id = detail_capture.source_payload_id
                left join league_analysis.source_capture timeline_capture
                  on timeline_capture.id = rm.timeline_source_capture_id
                left join league_analysis.source_payload timeline_payload
                  on timeline_payload.id = timeline_capture.source_payload_id
                where rm.match_id = ?
                """, (resultSet, rowNumber) -> new ExistingFixture(
                        resultSet.getString("detail_sha"),
                        resultSet.getString("timeline_sha"),
                        marker(resultSet.getString("detail_metadata")),
                        marker(resultSet.getString("timeline_metadata"))),
                MATCH_ID).stream().findFirst();
    }

    private boolean marker(String encoded) {
        if (encoded == null) {
            return false;
        }
        var metadata = json.readTree(encoded);
        var dataClass = metadata.path("dataClass");
        var fixtureVersion = metadata.path("fixtureVersion");
        return dataClass.isString()
                && fixtureVersion.isString()
                && "SYNTHETIC".equals(dataClass.stringValue())
                && FIXTURE_VERSION.equals(fixtureVersion.stringValue());
    }

    private ProviderDocument fixture(SourceKind kind, String name) {
        try (var input = DemoSeedCommand.class.getResourceAsStream("/demo/" + name)) {
            if (input == null) {
                throw new IllegalStateException("MISSING_DEMO_FIXTURE");
            }
            var payload = json.readTree(input);
            var encoded = payload.toString().getBytes(StandardCharsets.UTF_8);
            var metadata = json.valueToTree(Map.of(
                    "dataClass", "SYNTHETIC",
                    "fixtureVersion", FIXTURE_VERSION));
            return new ProviderDocument(
                    kind,
                    MATCH_ID,
                    CAPTURED_AT,
                    200,
                    "AMERICAS",
                    "NA1",
                    "16.17.1",
                    sha256(encoded),
                    encoded.length,
                    payload,
                    metadata,
                    "match-v5-v1",
                    1);
        } catch (IOException exception) {
            throw new IllegalStateException("INVALID_DEMO_FIXTURE", exception);
        }
    }

    private String sha256(byte[] encoded) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", exception);
        }
    }

    public enum SeedResult {
        CREATED,
        ALREADY_PRESENT
    }

    public record DemoMatch(
            String matchId,
            int focusParticipantId,
            int compareParticipantId,
            boolean invented) {}

    private record ExistingFixture(
            String detailSha,
            String timelineSha,
            boolean detailMarked,
            boolean timelineMarked) {
        private boolean hasMarker() {
            return detailMarked && timelineMarked;
        }

        private boolean matches(String expectedDetailSha, String expectedTimelineSha) {
            return hasMarker()
                    && expectedDetailSha.equals(detailSha)
                    && expectedTimelineSha.equals(timelineSha);
        }
    }
}
