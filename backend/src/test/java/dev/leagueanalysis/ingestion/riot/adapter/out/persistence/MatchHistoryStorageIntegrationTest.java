package dev.leagueanalysis.ingestion.riot.adapter.out.persistence;

import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.application.*;
import dev.leagueanalysis.ingestion.riot.domain.*;
import dev.leagueanalysis.support.PostgresTestConfiguration;
import dev.leagueanalysis.support.PublicLookupGatewayFixture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class MatchHistoryStorageIntegrationTest {
    static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    static final String PUUID = PublicLookupGatewayFixture.PUUID;
    static final String MATCH = PublicLookupGatewayFixture.MATCH_ID;
    @Autowired JdbcRiotIngestionStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    PublicLookupGatewayFixture fixture;
    final MatchV5Decoder decoder = new MatchV5Decoder();

    @BeforeEach void setup() {
        clear();
        fixture = new PublicLookupGatewayFixture(json, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @AfterEach void clear() {
        jdbc.execute("truncate table league_analysis.ingestion_run, league_analysis.source_payload cascade");
        jdbc.execute("delete from league_analysis.privacy_exclusion");
    }

    @Test void storesVerifiedQueuePagesAndReturnsMoreThanFiveRows() {
        var command = command(440, 0, null);
        var run = store.startPublicRun(command, NOW);
        assertThat(store.readPageCommand(run)).isEmpty();
        assertThat(store.readPublicRun(run).orElseThrow().gameName()).isEmpty();
        verifyIdentity(run, command);
        var ids = java.util.stream.IntStream.range(0, 20).mapToObj(i -> "NA1_" + (8000000000L + i)).toList();
        store.addItems(run, ids);
        for (String id : ids) materialize(run, id, 440, false);
        store.recordPageSize(run, 20);
        store.finishRun(run, IngestionRunStatus.COMPLETE, null, null, NOW.plusSeconds(30));
        var result = store.readPublicRun(run).orElseThrow();
        assertThat(result.queueId()).isEqualTo(440);
        assertThat(result.matches()).hasSize(20);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.lastUpdated()).isEqualTo(NOW.plusSeconds(30));
        assertThat(result.nextRefreshAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(store.readPageCommand(run)).contains(command);
        assertThat(json.writeValueAsString(result)).doesNotContain(PUUID, "source_capture", "bodySha256");
        jdbc.update("update league_analysis.riot_match set queue_id=420 where match_id=?", ids.getFirst());
        assertThat(store.readPublicRun(run).orElseThrow().matches()).hasSize(19);
        assertThat(store.readPublicRun(run).orElseThrow().hasMore()).isTrue();

        var nextCommand = command(440, 20, run);
        var next = verified(nextCommand, NOW.plusSeconds(45));
        store.recordPageSize(next, 0);
        store.finishRun(next, IngestionRunStatus.COMPLETE, null, null, NOW.plusSeconds(50));
        var page = store.findOlder(run).orElseThrow();
        assertThat(page.runId()).isEqualTo(next);
        assertThat(page.previousRunId()).isEqualTo(run);
        assertThat(page.status()).isEqualTo("EMPTY");
        assertThat(page.hasMore()).isFalse();
        assertThat(store.readPageCommand(next)).contains(nextCommand);
    }

    @Test void separatesQueueCacheAndUsesAccountWideFirstPageRefresh() {
        var solo = verified(command(420, 0, null), NOW);
        store.finishRun(solo, IngestionRunStatus.COMPLETE, null, null, NOW.plusSeconds(5));
        var flex = verified(command(440, 0, null), NOW.plusSeconds(60));
        store.finishRun(flex, IngestionRunStatus.COMPLETE, null, null, NOW.plusSeconds(65));
        var older = verified(command(440, 20, flex), NOW.plusSeconds(120));
        store.finishRun(older, IngestionRunStatus.COMPLETE, null, null, NOW.plusSeconds(125));
        var failed = verified(command(420, 0, null), NOW.plusSeconds(180));
        store.finishRun(failed, IngestionRunStatus.FAILED, "NOT_FOUND", "private", NOW.plusSeconds(185));
        assertThat(store.findFresh(command(420, 0, null), Instant.EPOCH).orElseThrow().runId()).isEqualTo(solo);
        assertThat(store.findFresh(command(440, 0, null), Instant.EPOCH).orElseThrow().runId()).isEqualTo(flex);
        assertThat(store.findFresh(command(430, 0, null), Instant.EPOCH)).isEmpty();
        assertThat(store.latestRefresh(command(420, 0, null))).contains(NOW.plusSeconds(60));
        assertThat(store.readPublicRun(solo).orElseThrow().nextRefreshAt()).isEqualTo(NOW.plusSeconds(960));
        assertThat(store.findFresh(command(440, 0, null), NOW.plusSeconds(66))).isEmpty();
    }

    @Test void verifiedAliasesShareTheSameAccountRefreshTimer() {
        var original = verified(command(420, 0, null), NOW);
        store.finishRun(original, IngestionRunStatus.COMPLETE, null, null, NOW.plusSeconds(5));
        var renamedCommand = new RiotIngestionCommand("Renamed", "NA1", 20, 440, 0, NOW.getEpochSecond(), null);
        var renamed = verified(renamedCommand, NOW.plusSeconds(60));
        store.finishRun(renamed, IngestionRunStatus.COMPLETE, null, null, NOW.plusSeconds(65));
        assertThat(store.latestRefresh(command(420, 0, null))).contains(NOW.plusSeconds(60));
        assertThat(store.latestRefresh(renamedCommand)).contains(NOW.plusSeconds(60));
    }

    @Test void reassignedRequestedAliasRejectsOldCacheAfterVerifiedLookupAcrossQueuesAndFailureStates() {
        for (var status : List.of(IngestionRunStatus.COMPLETE, IngestionRunStatus.PARTIAL, IngestionRunStatus.FAILED)) {
            var solo = new RiotIngestionCommand("Alias" + status, "NA1", 20, 420, 0, null, null);
            var old = verifiedAs(solo, "old-" + status, solo.gameName(), NOW);
            store.finishRun(old, IngestionRunStatus.COMPLETE, null, null, NOW.plusSeconds(5));
            var flex = new RiotIngestionCommand(solo.gameName(), "NA1", 20, 440, 0, null, null);
            var unverified = store.startPublicRun(flex, NOW.plusSeconds(20));
            store.finishRun(unverified, IngestionRunStatus.FAILED, "NOT_FOUND", "private", NOW.plusSeconds(25));
            assertThat(store.findFresh(solo, Instant.EPOCH).orElseThrow().runId()).isEqualTo(old);
            var reassigned = verifiedAs(flex, "new-" + status, solo.gameName(), NOW.plusSeconds(60));
            store.finishRun(reassigned, status, null, null, NOW.plusSeconds(65));
            assertThat(store.findFresh(solo, Instant.EPOCH)).isEmpty();
            if (status == IngestionRunStatus.COMPLETE) {
                assertThat(store.latestRefresh(solo)).contains(NOW.plusSeconds(60));
            } else {
                assertThat(store.latestRefresh(solo)).isEmpty();
            }
        }
    }

    @Test void newerCanonicalAliasEvidenceInvalidatesAnOlderRequestedAliasCache() {
        var solo = command(420, 0, null);
        var old = verifiedAs(solo, "old-canonical-owner", solo.gameName(), NOW);
        store.finishRun(old, IngestionRunStatus.COMPLETE, null, null, NOW.plusSeconds(5));
        var alternative = new RiotIngestionCommand("Alternative", "NA1", 20, 440, 0, null, null);
        var reassigned = verifiedAs(alternative, "new-canonical-owner", solo.gameName(), NOW.plusSeconds(60));
        store.finishRun(reassigned, IngestionRunStatus.COMPLETE, null, null, NOW.plusSeconds(65));
        // The current identity row can move to another canonical name; the original
        // verified account capture must still outrank the former owner's row.
        var laterAlias = new RiotIngestionCommand("AnotherAlias", "NA1", 20, 440, 0, null, null);
        var renamed = verifiedAs(laterAlias, "new-canonical-owner", "RenamedAgain", NOW.plusSeconds(120));
        store.finishRun(renamed, IngestionRunStatus.FAILED, null, null, NOW.plusSeconds(125));
        assertThat(store.findFresh(solo, Instant.EPOCH)).isEmpty();
        assertThat(store.latestRefresh(solo)).contains(NOW.plusSeconds(60));
    }

    @Test void newerRequestedAliasEvidenceOutranksAnOlderCanonicalAlias() {
        var solo = command(420, 0, null);
        var old = verifiedAs(solo, "old-requested-owner", solo.gameName(), NOW);
        store.finishRun(old, IngestionRunStatus.COMPLETE, null, null, NOW.plusSeconds(5));
        var flex = command(440, 0, null);
        var reassigned = verifiedAs(flex, "new-requested-owner", "DifferentCanonicalName", NOW.plusSeconds(60));
        store.finishRun(reassigned, IngestionRunStatus.COMPLETE, null, null, NOW.plusSeconds(65));
        assertThat(store.findFresh(solo, Instant.EPOCH)).isEmpty();
        assertThat(store.latestRefresh(solo)).contains(NOW.plusSeconds(60));
    }

    @Test void storedDetailRetainsOriginalEvidenceAndTimelineRunsStayOutOfHistory() {
        var run = verified(command(490, 0, null), NOW);
        store.addItems(run, List.of(MATCH));
        var detail = materialize(run, MATCH, 490, false);
        assertThat(store.hasSummary(MATCH, PUUID, 490)).isTrue();
        assertThat(store.hasSummary(MATCH, "someone-else", 490)).isFalse();
        assertThat(store.hasSummary(MATCH, PUUID, 420)).isFalse();
        assertThat(store.storedDetail(MATCH)).contains(detail.document());
        assertThat(store.readTimeline(MATCH).orElseThrow().status()).isEqualTo("NOT_REQUESTED");
        assertThat(store.readTimeline("NA1_9999999999")).isEmpty();
        var timelineRun = store.startTimelineRun(MATCH, NOW.plusSeconds(20));
        assertThat(store.readPublicRun(timelineRun)).isEmpty();
        assertThat(store.readPageCommand(timelineRun)).isEmpty();
        assertThat(store.readTimeline(MATCH).orElseThrow().status()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select queue_id from league_analysis.ingestion_run where id=?", Integer.class, timelineRun)).isEqualTo(490);
        store.finishRun(timelineRun, IngestionRunStatus.PARTIAL, "NOT_FOUND", "private provider body", NOW.plusSeconds(30));
        var unavailable = store.readTimeline(MATCH).orElseThrow();
        assertThat(unavailable.status()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.message()).doesNotContain("private", "Riot ID");
        var retryRun = store.startTimelineRun(MATCH, NOW.plusSeconds(40));
        store.stopForCooldown(retryRun, NOW.plusSeconds(160), NOW.plusSeconds(40));
        assertThat(store.readTimeline(MATCH).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(store.readTimeline(MATCH).orElseThrow().retryNotBefore()).isEqualTo(NOW.plusSeconds(160));
        var success = store.startTimelineRun(MATCH, NOW.plusSeconds(170));
        var reused = store.saveCapture(success, store.storedDetail(MATCH).orElseThrow());
        var timeline = store.saveCapture(success, fixture.fetchMatchTimeline(MATCH));
        store.materialize(success, MATCH, decoder.decode(reused, Optional.of(timeline)));
        store.finishRun(success, IngestionRunStatus.COMPLETE, null, null, NOW.plusSeconds(180));
        assertThat(store.readTimeline(MATCH).orElseThrow().status()).isEqualTo("AVAILABLE");
        assertThat(store.storedDetail(MATCH)).contains(detail.document());
        assertThat(store.isCompleteMatch(MATCH, PUUID)).isTrue();
    }

    @Test void summaryCannotDowngradeCompleteMatchAndStillValidatesCaptureProvenance() {
        var run = verified(command(420, 0, null), NOW);
        store.addItems(run, List.of(MATCH));
        var original = materialize(run, MATCH, 420, true);
        var originalTimeline = jdbc.queryForObject("select timeline_source_capture_id from league_analysis.riot_match where match_id=?", UUID.class, MATCH);
        int eventCount = jdbc.queryForObject("select count(*) from league_analysis.match_event", Integer.class);
        var later = verified(command(420, 0, null), NOW.plusSeconds(60));
        store.addItems(later, List.of(MATCH));
        var summary = store.saveCapture(later, detail(MATCH, 420));
        store.materialize(later, MATCH, decoder.decode(summary, Optional.empty()));
        assertThat(jdbc.queryForObject("select timeline_source_capture_id from league_analysis.riot_match where match_id=?", UUID.class, MATCH)).isEqualTo(originalTimeline);
        assertThat(jdbc.queryForObject("select detail_source_capture_id from league_analysis.riot_match where match_id=?", UUID.class, MATCH)).isEqualTo(original.captureId());
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.match_event", Integer.class)).isEqualTo(eventCount).isPositive();
        assertThatThrownBy(() -> store.materialize(later, MATCH, decoder.decode(original, Optional.empty())))
                .hasRootCauseInstanceOf(IllegalArgumentException.class).hasMessage("MISMATCHED_CAPTURE_PROVENANCE");
    }

    @Test void pageLinksCannotReviveAnUnverifiedOrExcludedIdentity() {
        var run = verified(command(420, 0, null), NOW);
        store.finishRun(run, IngestionRunStatus.COMPLETE, null, null, NOW);
        assertThat(store.matchesPageIdentity(run, PUUID)).isTrue();
        assertThat(store.matchesPageIdentity(run, "reassigned-puuid")).isFalse();
        jdbc.update("insert into league_analysis.privacy_exclusion values ('puuid',league_analysis.privacy_hash(?))", PUUID);
        assertThat(store.readPageCommand(run)).isEmpty();
        assertThat(store.matchesPageIdentity(run, PUUID)).isFalse();
        assertThat(store.findFresh(command(420, 0, null), Instant.EPOCH)).isEmpty();
        assertThat(store.latestRefresh(command(420, 0, null))).isEmpty();
    }

    @Test void previousPageDeletionClearsLinkWithoutInventingAFirstPage() {
        var previous = store.startPublicRun(command(420, 0, null), NOW);
        var child = verified(command(420, 20, previous), NOW.plusSeconds(10));
        store.discardRun(previous);
        assertThat(store.readPageCommand(child).orElseThrow().start()).isEqualTo(20);
        assertThat(store.readPageCommand(child).orElseThrow().previousRunId()).isNull();
        assertThat(store.latestRefresh(command(420, 0, null))).isEmpty();
    }

    @Test void storedDetailAndTimelineRecheckExcludedDocuments() {
        var run = verified(command(420, 0, null), NOW);
        store.addItems(run, List.of(MATCH));
        materialize(run, MATCH, 420, false);
        jdbc.update("insert into league_analysis.privacy_exclusion values ('puuid',league_analysis.privacy_hash(?))", PUUID);
        assertThat(store.storedDetail(MATCH)).isEmpty();
        assertThat(store.readTimeline(MATCH)).isEmpty();
        assertThatThrownBy(() -> store.startTimelineRun(MATCH, NOW)).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    RiotIngestionCommand command(int queue, int start, UUID previous) {
        return new RiotIngestionCommand("Invented", "NA1", 20, queue, start, NOW.getEpochSecond(), previous);
    }
    UUID verified(RiotIngestionCommand command, Instant now) {
        var run = store.startPublicRun(command, now);
        verifyIdentity(run, command);
        return run;
    }
    UUID verifiedAs(RiotIngestionCommand command, String puuid, String canonicalName, Instant observedAt) {
        var run = store.startPublicRun(command, observedAt);
        var body = json.createObjectNode().put("puuid", puuid).put("gameName", canonicalName).put("tagLine", command.tagLine());
        var bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        String hash = dev.leagueanalysis.privacy.PrivacyHash.of(body.toString());
        var document = new ProviderDocument(SourceKind.ACCOUNT, command.gameName() + "#" + command.tagLine(),
                observedAt, 200, "AMERICAS", "NA1", null, hash, bytes.length, body, json.createObjectNode(), "fixture", 1);
        var source = store.saveCapture(run, document);
        store.recordResolvedAccount(run, new RiotAccount(puuid, canonicalName, command.tagLine()), source);
        store.recordVerifiedRequestedIdentity(run, command);
        return run;
    }
    void verifyIdentity(UUID run, RiotIngestionCommand command) {
        var lookup = fixture.resolveAccount(new RiotId(command.gameName(), command.tagLine()));
        var capture = store.saveCapture(run, lookup.source());
        store.recordResolvedAccount(run, lookup.account(), capture);
        store.recordVerifiedRequestedIdentity(run, command);
    }
    CapturedDocument materialize(UUID run, String match, int queue, boolean withTimeline) {
        var capture = store.saveCapture(run, detail(match, queue));
        var timeline = withTimeline ? Optional.of(store.saveCapture(run, fixture.fetchMatchTimeline(match))) : Optional.<CapturedDocument>empty();
        store.materialize(run, match, decoder.decode(capture, timeline));
        store.markItemTerminal(run, match, IngestionItemStatus.COMPLETE, null, null, NOW);
        return capture;
    }
    ProviderDocument detail(String match, int queue) {
        try {
            var original = fixture.fetchMatchDetail(match);
            var payload = original.payload();
            ((ObjectNode) payload.get("info")).put("queueId", queue);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            return new ProviderDocument(original.kind(), original.resourceKey(), original.capturedAt(), original.httpStatus(),
                    original.regionalRoute(), original.platformRoute(), original.providerGameVersion(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length,
                    payload, json.createObjectNode().put("fixture-metadata", "original"), "original-parser", 2);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
