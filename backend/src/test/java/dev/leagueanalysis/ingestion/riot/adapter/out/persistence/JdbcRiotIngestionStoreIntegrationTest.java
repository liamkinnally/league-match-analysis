package dev.leagueanalysis.ingestion.riot.adapter.out.persistence;

import dev.leagueanalysis.ingestion.riot.application.IngestionItemStatus;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionCommand;
import dev.leagueanalysis.evidence.domain.CanonicalEventKind;
import dev.leagueanalysis.evidence.domain.CoverageStatus;
import dev.leagueanalysis.ingestion.riot.domain.CapturedDocument;
import dev.leagueanalysis.ingestion.riot.domain.EvidenceCoverage;
import dev.leagueanalysis.ingestion.riot.domain.MatchEvent;
import dev.leagueanalysis.ingestion.riot.domain.MatchFact;
import dev.leagueanalysis.ingestion.riot.domain.ParticipantFact;
import dev.leagueanalysis.ingestion.riot.domain.ParticipantStateObservation;
import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.RiotAccount;
import dev.leagueanalysis.ingestion.riot.domain.RiotMatchMaterialization;
import dev.leagueanalysis.ingestion.riot.domain.SourceKind;
import dev.leagueanalysis.ingestion.riot.domain.TeamFact;
import dev.leagueanalysis.support.PostgresTestConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class JdbcRiotIngestionStoreIntegrationTest {
    private static final Instant FIRST = Instant.parse("2026-09-03T12:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-09-03T12:01:00Z");
    private static final String MATCH_ID = "NA1_9999999999";

    @Autowired
    JdbcRiotIngestionStore store;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("truncate table league_analysis.ingestion_run, league_analysis.source_payload cascade");
    }

    @AfterEach
    void leaveDatabaseClean() {
        clearDatabase();
    }

    @Test
    void deduplicatesExactPayloadsButRecordsEachCapture() {
        var firstRun = startRun();
        var secondRun = startRun();
        var source = document(SourceKind.ACCOUNT, "invented#NA1", "a".repeat(64), 1, FIRST);

        var first = store.saveCapture(firstRun, source);
        var second = store.saveCapture(secondRun, source);

        assertThat(first.payloadId()).isEqualTo(second.payloadId());
        assertThat(first.captureId()).isNotEqualTo(second.captureId());
        assertThat(count("source_payload")).isEqualTo(1);
        assertThat(count("source_capture")).isEqualTo(2);
    }

    @Test
    void assignsCaptureIdsAndAttachesMatchDocumentsToTheRunItem() {
        var runId = startRun();
        store.addItems(runId, List.of(MATCH_ID));

        var detail = store.saveCapture(
                runId, document(SourceKind.MATCH_DETAIL, MATCH_ID, "a".repeat(64), 1, FIRST));
        var timeline = store.saveCapture(
                runId, document(SourceKind.MATCH_TIMELINE, MATCH_ID, "b".repeat(64), 1, SECOND));

        assertThat(detail.payloadId()).isNotNull();
        assertThat(detail.captureId()).isNotNull();
        assertThat(timeline.payloadId()).isNotNull();
        assertThat(timeline.captureId()).isNotNull();
        assertThat(jdbc.queryForMap("""
                        select detail_capture_id, timeline_capture_id
                        from league_analysis.ingestion_item
                        where ingestion_run_id = ? and match_id = ?
                        """, runId, MATCH_ID))
                .containsEntry("detail_capture_id", detail.captureId())
                .containsEntry("timeline_capture_id", timeline.captureId());
    }

    @Test
    void olderAccountObservationCannotRegressTheLatestRiotIdentity() {
        var firstRun = startRun();
        var firstSource = store.saveCapture(
                firstRun, document(SourceKind.ACCOUNT, "OldName#NA1", "a".repeat(64), 1, FIRST));
        var secondRun = startRun();
        var secondSource = store.saveCapture(
                secondRun, document(SourceKind.ACCOUNT, "NewName#NA1", "b".repeat(64), 1, SECOND));

        store.recordResolvedAccount(
                secondRun, new RiotAccount("invented-puuid", "NewName", "NA1"), secondSource);
        store.recordResolvedAccount(
                firstRun, new RiotAccount("invented-puuid", "OldName", "NA1"), firstSource);

        assertThat(count("riot_identity")).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                        select game_name, tag_line,
                               extract(epoch from first_observed_at)::bigint as first_observed_epoch,
                               extract(epoch from last_observed_at)::bigint as last_observed_epoch,
                               last_source_capture_id
                        from league_analysis.riot_identity
                        where puuid = 'invented-puuid'
                        """))
                .containsEntry("game_name", "NewName")
                .containsEntry("tag_line", "NA1")
                .containsEntry("first_observed_epoch", FIRST.getEpochSecond())
                .containsEntry("last_observed_epoch", SECOND.getEpochSecond())
                .containsEntry("last_source_capture_id", secondSource.captureId());
    }

    @Test
    void concurrentAccountObservationsKeepTheNewestIdentity() throws Exception {
        var firstRun = startRun();
        var firstSource = store.saveCapture(
                firstRun, document(SourceKind.ACCOUNT, "OldName#NA1", "a".repeat(64), 1, FIRST));
        var secondRun = startRun();
        var secondSource = store.saveCapture(
                secondRun, document(SourceKind.ACCOUNT, "NewName#NA1", "b".repeat(64), 1, SECOND));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var oldWrite = executor.submit(() -> {
                ready.countDown();
                start.await();
                store.recordResolvedAccount(
                        firstRun, new RiotAccount("invented-puuid", "OldName", "NA1"), firstSource);
                return null;
            });
            var newWrite = executor.submit(() -> {
                ready.countDown();
                start.await();
                store.recordResolvedAccount(
                        secondRun, new RiotAccount("invented-puuid", "NewName", "NA1"), secondSource);
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            oldWrite.get(5, TimeUnit.SECONDS);
            newWrite.get(5, TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForMap("""
                        select game_name,
                               extract(epoch from first_observed_at)::bigint as first_observed_epoch,
                               extract(epoch from last_observed_at)::bigint as last_observed_epoch,
                               last_source_capture_id
                        from league_analysis.riot_identity
                        where puuid = 'invented-puuid'
                        """))
                .containsEntry("game_name", "NewName")
                .containsEntry("first_observed_epoch", FIRST.getEpochSecond())
                .containsEntry("last_observed_epoch", SECOND.getEpochSecond())
                .containsEntry("last_source_capture_id", secondSource.captureId());
    }

    @Test
    void olderMatchParticipantCannotRegressANewerAccountObservation() {
        var accountRun = startRun();
        var accountSource = store.saveCapture(
                accountRun, document(SourceKind.ACCOUNT, "NewName#NA1", "a".repeat(64), 1, SECOND));
        store.recordResolvedAccount(
                accountRun, new RiotAccount("invented-puuid-1", "NewName", "NA1"), accountSource);

        var matchRun = startRun();
        store.addItems(matchRun, List.of(MATCH_ID));
        var detail = store.saveCapture(
                matchRun, document(SourceKind.MATCH_DETAIL, MATCH_ID, "b".repeat(64), 1, FIRST));
        var timeline = store.saveCapture(
                matchRun, document(SourceKind.MATCH_TIMELINE, MATCH_ID, "c".repeat(64), 1, FIRST));
        store.materialize(matchRun, MATCH_ID, materialization(detail, timeline, 10, 2, false));

        assertThat(jdbc.queryForMap("""
                        select game_name,
                               extract(epoch from first_observed_at)::bigint as first_observed_epoch,
                               extract(epoch from last_observed_at)::bigint as last_observed_epoch,
                               last_source_capture_id
                        from league_analysis.riot_identity
                        where puuid = 'invented-puuid-1'
                        """))
                .containsEntry("game_name", "NewName")
                .containsEntry("first_observed_epoch", FIRST.getEpochSecond())
                .containsEntry("last_observed_epoch", SECOND.getEpochSecond())
                .containsEntry("last_source_capture_id", accountSource.captureId());
    }

    @Test
    void rematerializingTheSameMatchKeepsStableCurrentCounts() {
        var runId = startRun();
        store.addItems(runId, List.of(MATCH_ID));
        var detail = store.saveCapture(
                runId, document(SourceKind.MATCH_DETAIL, MATCH_ID, "a".repeat(64), 1, FIRST));
        var timeline = store.saveCapture(
                runId, document(SourceKind.MATCH_TIMELINE, MATCH_ID, "b".repeat(64), 1, FIRST));
        var materialization = materialization(detail, timeline, 10, 2, false);

        store.materialize(runId, MATCH_ID, materialization);
        store.materialize(runId, MATCH_ID, materialization);

        assertThat(count("riot_match")).isEqualTo(1);
        assertThat(count("riot_team")).isEqualTo(2);
        assertThat(count("riot_participant")).isEqualTo(10);
        assertThat(count("participant_state_observation")).isEqualTo(10);
        assertThat(count("match_event")).isEqualTo(2);
    }

    @Test
    void changedCaptureHistoryReplacesOnlyCurrentNormalizedChildren() {
        var runId = startRun();
        store.addItems(runId, List.of(MATCH_ID));
        var first = store.saveCapture(
                runId, document(SourceKind.MATCH_DETAIL, MATCH_ID, "a".repeat(64), 1, FIRST));
        var firstTimeline = store.saveCapture(
                runId, document(SourceKind.MATCH_TIMELINE, MATCH_ID, "c".repeat(64), 1, FIRST));
        store.materialize(runId, MATCH_ID, materialization(first, firstTimeline, 10, 2, false));

        var second = store.saveCapture(
                runId, document(SourceKind.MATCH_DETAIL, MATCH_ID, "b".repeat(64), 2, SECOND));
        var secondTimeline = store.saveCapture(
                runId, document(SourceKind.MATCH_TIMELINE, MATCH_ID, "d".repeat(64), 2, SECOND));
        store.materialize(runId, MATCH_ID, materialization(second, secondTimeline, 4, 1, false));

        assertThat(count("source_payload")).isEqualTo(4);
        assertThat(count("source_capture")).isEqualTo(4);
        assertThat(count("riot_match")).isEqualTo(1);
        assertThat(count("riot_participant")).isEqualTo(10);
        assertThat(count("participant_state_observation")).isEqualTo(4);
        assertThat(count("match_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                        select detail_source_capture_id
                        from league_analysis.riot_match where match_id = ?
                """, UUID.class, MATCH_ID)).isEqualTo(second.captureId());
    }

    @Test
    void replacesEndItemInventoryInSlotOrderWhileKeepingCaptureHistory() {
        var runId = startRun();
        store.addItems(runId, List.of(MATCH_ID));
        var first = store.saveCapture(
                runId, document(SourceKind.MATCH_DETAIL, MATCH_ID, "a".repeat(64), 1, FIRST));
        var firstTimeline = store.saveCapture(
                runId, document(SourceKind.MATCH_TIMELINE, MATCH_ID, "b".repeat(64), 1, FIRST));
        store.materialize(runId, MATCH_ID, materialization(
                first, firstTimeline, 0, 0, false, List.of(1056, 2003, 0, 0, 0, 0, 3340)));

        var second = store.saveCapture(
                runId, document(SourceKind.MATCH_DETAIL, MATCH_ID, "c".repeat(64), 2, SECOND));
        var secondTimeline = store.saveCapture(
                runId, document(SourceKind.MATCH_TIMELINE, MATCH_ID, "d".repeat(64), 2, SECOND));
        store.materialize(runId, MATCH_ID, materialization(
                second, secondTimeline, 0, 0, false, List.of(3006, 3100, 3118, 3157, 3089, 0, 3364)));

        assertThat(count("source_payload")).isEqualTo(4);
        assertThat(count("source_capture")).isEqualTo(4);
        assertThat(jdbc.queryForList("""
                        select item_id::integer
                        from league_analysis.riot_participant,
                             jsonb_array_elements_text(end_item_ids) with ordinality as items(item_id, slot)
                        where match_id = ? and participant_id = 1
                        order by slot
                        """, Integer.class, MATCH_ID))
                .containsExactly(3006, 3100, 3118, 3157, 3089, 0, 3364);
    }

    @Test
    void persistsDetailFactsAndUnavailableCoverageWithoutTimeline() {
        var runId = startRun();
        store.addItems(runId, List.of(MATCH_ID));
        var detail = store.saveCapture(
                runId, document(SourceKind.MATCH_DETAIL, MATCH_ID, "a".repeat(64), 1, FIRST));

        store.materialize(runId, MATCH_ID, materialization(detail, null, 0, 0, true));

        assertThat(count("riot_match")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                        select count(*) from league_analysis.evidence_coverage
                        where match_id = ? and source_kind = 'MATCH_TIMELINE'
                          and status = 'UNAVAILABLE' and source_capture_id is null
                        """, Integer.class, MATCH_ID)).isEqualTo(1);
    }

    @Test
    void failedReplacementRollsBackNormalizedRowsButKeepsCaptureAndLaterFailureStatus() {
        var runId = startRun();
        store.addItems(runId, List.of(MATCH_ID));
        var first = store.saveCapture(
                runId, document(SourceKind.MATCH_DETAIL, MATCH_ID, "a".repeat(64), 1, FIRST));
        var firstTimeline = store.saveCapture(
                runId, document(SourceKind.MATCH_TIMELINE, MATCH_ID, "c".repeat(64), 1, FIRST));
        store.materialize(runId, MATCH_ID, materialization(first, firstTimeline, 10, 2, false));

        var second = store.saveCapture(
                runId, document(SourceKind.MATCH_DETAIL, MATCH_ID, "b".repeat(64), 2, SECOND));
        var secondTimeline = store.saveCapture(
                runId, document(SourceKind.MATCH_TIMELINE, MATCH_ID, "d".repeat(64), 2, SECOND));
        var invalid = invalidTeamMaterialization(second, secondTimeline);

        assertThatThrownBy(() -> store.materialize(runId, MATCH_ID, invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("source_capture")).isEqualTo(4);
        assertThat(count("riot_participant")).isEqualTo(10);
        assertThat(count("match_event")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                        select detail_source_capture_id
                        from league_analysis.riot_match where match_id = ?
                        """, UUID.class, MATCH_ID)).isEqualTo(first.captureId());

        store.markItemTerminal(
                runId, MATCH_ID, IngestionItemStatus.FAILED,
                "MATERIALIZATION_FAILED", "Materialization failed", SECOND);
        assertThat(jdbc.queryForObject("""
                        select status from league_analysis.ingestion_item
                        where ingestion_run_id = ? and match_id = ?
                """, String.class, runId, MATCH_ID)).isEqualTo("FAILED");
    }

    @Test
    void rejectsCaptureProvenanceOutsideTheLockedRunItem() {
        var runId = startRun();
        store.addItems(runId, List.of(MATCH_ID));
        var otherRun = startRun();
        var otherRunDetail = store.saveCapture(
                otherRun, document(SourceKind.MATCH_DETAIL, MATCH_ID, "c".repeat(64), 1, FIRST));

        assertThatThrownBy(() -> store.materialize(
                        runId, MATCH_ID, materialization(otherRunDetail, null, 0, 0, true)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("MISMATCHED_CAPTURE_PROVENANCE");
    }

    @Test
    void rejectsWrongCaptureKindAndMismatchedChildProvenance() {
        var runId = startRun();
        store.addItems(runId, List.of(MATCH_ID));
        var detail = store.saveCapture(
                runId, document(SourceKind.MATCH_DETAIL, MATCH_ID, "a".repeat(64), 1, FIRST));
        var timeline = store.saveCapture(
                runId, document(SourceKind.MATCH_TIMELINE, MATCH_ID, "b".repeat(64), 1, FIRST));

        assertThatThrownBy(() -> store.materialize(
                        runId, MATCH_ID, materialization(timeline, null, 0, 0, true)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("MISMATCHED_CAPTURE_PROVENANCE");

        var valid = materialization(detail, timeline, 1, 0, false);
        var observation = valid.observations().getFirst();
        var wrongSourceObservation = new ParticipantStateObservation(
                observation.id(), observation.matchId(), observation.participantId(),
                observation.representedAtMs(), observation.x(), observation.y(),
                observation.currentGold(), observation.totalGold(), observation.level(),
                observation.xp(), observation.minionsKilled(), observation.jungleMinionsKilled(),
                detail.captureId(), observation.methodVersion());
        var wrongSource = new RiotMatchMaterialization(
                valid.match(), valid.teams(), valid.participants(),
                List.of(wrongSourceObservation), valid.events(), valid.coverage());

        assertThatThrownBy(() -> store.materialize(runId, MATCH_ID, wrongSource))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("MISMATCHED_CHILD_PROVENANCE");
    }

    @Test
    void rejectsChildRowsFromAnotherAggregateBeforeDatabaseWrites() {
        var runId = startRun();
        store.addItems(runId, List.of(MATCH_ID));
        var detail = store.saveCapture(
                runId, document(SourceKind.MATCH_DETAIL, MATCH_ID, "a".repeat(64), 1, FIRST));
        var valid = materialization(detail, null, 0, 0, true);
        var mismatchedTeams = List.of(
                new TeamFact("NA1_123", 100, true, JsonNodeFactory.instance.objectNode()),
                valid.teams().get(1));
        var mismatched = new RiotMatchMaterialization(
                valid.match(), mismatchedTeams, valid.participants(),
                valid.observations(), valid.events(), valid.coverage());

        assertThatThrownBy(() -> store.materialize(runId, MATCH_ID, mismatched))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("MISMATCHED_CHILD_MATCH");
        assertThat(count("riot_match")).isZero();
    }

    @Test
    void serializesConcurrentReplacementOfTheSameMatch() throws Exception {
        var initialRun = startRun();
        store.addItems(initialRun, List.of(MATCH_ID));
        var initialDetail = store.saveCapture(
                initialRun, document(SourceKind.MATCH_DETAIL, MATCH_ID, "0".repeat(64), 1, FIRST));
        var initialTimeline = store.saveCapture(
                initialRun, document(SourceKind.MATCH_TIMELINE, MATCH_ID, "1".repeat(64), 1, FIRST));
        store.materialize(
                initialRun, MATCH_ID, materialization(initialDetail, initialTimeline, 10, 2, false));

        var firstRun = startRun();
        store.addItems(firstRun, List.of(MATCH_ID));
        var firstDetail = store.saveCapture(
                firstRun, document(SourceKind.MATCH_DETAIL, MATCH_ID, "2".repeat(64), 1, SECOND));
        var firstTimeline = store.saveCapture(
                firstRun, document(SourceKind.MATCH_TIMELINE, MATCH_ID, "3".repeat(64), 1, SECOND));
        var secondRun = startRun();
        store.addItems(secondRun, List.of(MATCH_ID));
        var secondDetail = store.saveCapture(
                secondRun, document(SourceKind.MATCH_DETAIL, MATCH_ID, "4".repeat(64), 1, SECOND));
        var secondTimeline = store.saveCapture(
                secondRun, document(SourceKind.MATCH_TIMELINE, MATCH_ID, "5".repeat(64), 1, SECOND));

        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                store.materialize(
                        firstRun, MATCH_ID, materialization(firstDetail, firstTimeline, 10, 2, false));
                return null;
            });
            var second = executor.submit(() -> {
                start.await();
                store.materialize(
                        secondRun, MATCH_ID, materialization(secondDetail, secondTimeline, 10, 2, false));
                return null;
            });

            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertThat(count("riot_match")).isEqualTo(1);
        assertThat(count("riot_team")).isEqualTo(2);
        assertThat(count("riot_participant")).isEqualTo(10);
        assertThat(count("participant_state_observation")).isEqualTo(10);
        assertThat(count("match_event")).isEqualTo(2);
        assertThat(count("evidence_coverage")).isEqualTo(1);
    }

    private UUID startRun() {
        return store.startRun(new RiotIngestionCommand("InventedPlayer", "NA1", 5), FIRST);
    }

    private ProviderDocument document(
            SourceKind kind,
            String resourceKey,
            String hash,
            int attempt,
            Instant capturedAt) {
        var payload = JsonNodeFactory.instance.objectNode()
                .put("kind", kind.name())
                .put("resource", resourceKey)
                .put("attempt", attempt);
        return new ProviderDocument(
                kind, resourceKey, capturedAt, 200, "AMERICAS", "NA1", "16.17.810.4348",
                hash, payload.toString().length(), payload,
                JsonNodeFactory.instance.objectNode().put("content-type", "application/json"),
                "test-parser-v1", attempt);
    }

    private RiotMatchMaterialization materialization(
            CapturedDocument detail,
            CapturedDocument timeline,
            int observationCount,
            int eventCount,
            boolean unavailableTimeline) {
        return materialization(
                detail, timeline, observationCount, eventCount, unavailableTimeline,
                List.of(1056, 2003, 0, 0, 0, 0, 3340));
    }

    private RiotMatchMaterialization materialization(
            CapturedDocument detail,
            CapturedDocument timeline,
            int observationCount,
            int eventCount,
            boolean unavailableTimeline,
            List<Integer> endItemIds) {
        var participants = participants(false, endItemIds);
        var observations = new ArrayList<ParticipantStateObservation>();
        for (var index = 0; index < observationCount; index++) {
            var participantId = (index % 10) + 1;
            observations.add(new ParticipantStateObservation(
                    UUID.randomUUID(), MATCH_ID, participantId, 60_000L + index,
                    1000 + index, 2000 + index, 500, 1200, 3, 900,
                    12, 0, timeline == null ? detail.captureId() : timeline.captureId(), "test-v1"));
        }
        var events = new ArrayList<MatchEvent>();
        for (var index = 0; index < eventCount; index++) {
            events.add(new MatchEvent(
                    UUID.randomUUID(), MATCH_ID, 59_977L + index, 60_000L,
                    index, "FUTURE_EVENT", CanonicalEventKind.OTHER,
                    1, 2, 7000, 7100,
                    JsonNodeFactory.instance.objectNode().put("index", index),
                    timeline == null ? detail.captureId() : timeline.captureId(), "test-v1"));
        }
        var coverage = List.of(new EvidenceCoverage(
                UUID.randomUUID(), MATCH_ID, SourceKind.MATCH_TIMELINE, "participant_positions",
                unavailableTimeline ? CoverageStatus.UNAVAILABLE : CoverageStatus.OBSERVED,
                unavailableTimeline ? null : 60_000L,
                unavailableTimeline ? null : 60_000L,
                JsonNodeFactory.instance.objectNode(),
                unavailableTimeline ? null : timeline.captureId(), "test-v1"));
        return new RiotMatchMaterialization(
                match(detail, timeline), teams(), participants, observations, events, coverage);
    }

    private RiotMatchMaterialization invalidTeamMaterialization(
            CapturedDocument detail,
            CapturedDocument timeline) {
        return new RiotMatchMaterialization(
                match(detail, timeline), teams(), participants(true, List.of(1056, 2003, 0, 0, 0, 0, 3340)),
                List.of(), List.of(), List.of());
    }

    private MatchFact match(CapturedDocument detail, CapturedDocument timeline) {
        return new MatchFact(
                MATCH_ID, 9999999999L, 420, 11, "CLASSIC", "MATCHED_GAME",
                "16.17.810.4348", "2", 1788264000000L,
                1788264000000L, 1788265800000L, 1800,
                detail.captureId(), timeline == null ? null : timeline.captureId(), "test-v1");
    }

    private List<TeamFact> teams() {
        return List.of(
                new TeamFact(MATCH_ID, 100, true, JsonNodeFactory.instance.objectNode()),
                new TeamFact(MATCH_ID, 200, false, JsonNodeFactory.instance.objectNode()));
    }

    private List<ParticipantFact> participants(boolean invalidLastTeam, List<Integer> endItemIds) {
        var participants = new ArrayList<ParticipantFact>();
        for (var participantId = 1; participantId <= 10; participantId++) {
            participants.add(new ParticipantFact(
                    MATCH_ID, participantId, "invented-puuid-" + participantId,
                    "Invented" + participantId, "NA1",
                    invalidLastTeam && participantId == 10 ? 300 : participantId <= 5 ? 100 : 200,
                    100 + participantId, "Champion" + participantId, "MIDDLE",
                    1, 1, 1, 100, 0, 10000, 9000, 20,
                    4, 14, participantId <= 5, endItemIds));
        }
        return participants;
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from league_analysis." + table, Integer.class);
    }
}
