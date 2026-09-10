package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.domain.CapturedDocument;
import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.RiotAccount;
import dev.leagueanalysis.ingestion.riot.domain.RiotId;
import dev.leagueanalysis.ingestion.riot.domain.RiotMatchMaterialization;
import dev.leagueanalysis.ingestion.riot.domain.SourceKind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
class RiotIngestionServiceTest {
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC);
    private static final RiotIngestionCommand COMMAND = new RiotIngestionCommand("InventedPlayer", "NA1", 5);

    @Test
    void orchestratesEveryPersistedDocumentInExactOrderAndCompletes() {
        var events = new ArrayList<String>();
        var gateway = new FakeGateway(events, List.of("NA1_101", "NA1_102"));
        var store = new FakeStore(events);
        var decoder = new FakeDecoder();
        var service = new RiotIngestionService(gateway, decoder, store, CLOCK);

        var result = service.ingest(COMMAND);

        assertThat(result).isEqualTo(new RiotIngestionResult(RUN_ID, IngestionRunStatus.COMPLETE, 2, 2, 0, 0));
        assertThat(events).containsExactly(
                "store.start",
                "gateway.account",
                "store.save.ACCOUNT",
                "store.account",
                "gateway.list",
                "store.save.MATCH_LIST",
                "store.items",
                "store.running.NA1_101",
                "gateway.detail.NA1_101",
                "store.save.MATCH_DETAIL",
                "gateway.timeline.NA1_101",
                "store.save.MATCH_TIMELINE",
                "store.materialize.NA1_101",
                "store.terminal.NA1_101.COMPLETE",
                "store.running.NA1_102",
                "gateway.detail.NA1_102",
                "store.save.MATCH_DETAIL",
                "gateway.timeline.NA1_102",
                "store.save.MATCH_TIMELINE",
                "store.materialize.NA1_102",
                "store.terminal.NA1_102.COMPLETE",
                "store.finish.COMPLETE");
        assertThat(decoder.calls).isEqualTo(2);
    }

    @Test
    void treatsMissingTimelineAsDetailOnlyPartialAndContinues() {
        var events = new ArrayList<String>();
        var gateway = new FakeGateway(events, List.of("NA1_101", "NA1_102"));
        gateway.timelineFailures.put("NA1_101", RiotFailureCode.NOT_FOUND);
        var store = new FakeStore(events);
        var service = new RiotIngestionService(gateway, new FakeDecoder(), store, CLOCK);

        var result = service.ingest(COMMAND);

        assertThat(result.status()).isEqualTo(IngestionRunStatus.PARTIAL);
        assertThat(result.complete()).isEqualTo(1);
        assertThat(result.partial()).isEqualTo(1);
        assertThat(store.terminals).extracting(Terminal::status)
                .containsExactly(IngestionItemStatus.PARTIAL, IngestionItemStatus.COMPLETE);
        assertThat(events).contains("gateway.detail.NA1_102", "gateway.timeline.NA1_102");
    }

    @Test
    void failsMissingDetailAndContinuesWithLaterMatches() {
        var events = new ArrayList<String>();
        var gateway = new FakeGateway(events, List.of("NA1_101", "NA1_102"));
        gateway.detailFailures.put("NA1_101", RiotFailureCode.UPSTREAM_UNAVAILABLE);
        var store = new FakeStore(events);
        var service = new RiotIngestionService(gateway, new FakeDecoder(), store, CLOCK);

        var result = service.ingest(COMMAND);

        assertThat(result).isEqualTo(new RiotIngestionResult(RUN_ID, IngestionRunStatus.PARTIAL, 2, 1, 0, 1));
        assertThat(store.terminals).extracting(Terminal::status)
                .containsExactly(IngestionItemStatus.FAILED, IngestionItemStatus.COMPLETE);
        assertThat(events).doesNotContain("gateway.timeline.NA1_101");
        assertThat(events).contains("gateway.detail.NA1_102");
    }

    @Test
    void authenticationFailureBeforeItemsFailsTheRunWithoutMatchCalls() {
        for (var failurePoint : List.of("account", "list")) {
            var events = new ArrayList<String>();
            var gateway = new FakeGateway(events, List.of("NA1_101"));
            if (failurePoint.equals("account")) {
                gateway.accountFailure = RiotFailureCode.AUTHENTICATION_FAILED;
            } else {
                gateway.listFailure = RiotFailureCode.AUTHENTICATION_FAILED;
            }
            var store = new FakeStore(events);
            var result = new RiotIngestionService(gateway, new FakeDecoder(), store, CLOCK)
                    .ingest(COMMAND);

            assertThat(result.status()).isEqualTo(IngestionRunStatus.FAILED);
            assertThat(store.finish.code()).isEqualTo("AUTHENTICATION_FAILED");
            assertThat(events).noneMatch(event -> event.startsWith("gateway.detail."));
        }
    }

    @Test
    void exhaustedRateLimitStopsRequestsAndSkipsUnstartedItems() {
        var events = new ArrayList<String>();
        var gateway = new FakeGateway(events, List.of("NA1_101", "NA1_102", "NA1_103"));
        gateway.timelineFailures.put("NA1_101", RiotFailureCode.RATE_LIMITED);
        var store = new FakeStore(events);
        var result = new RiotIngestionService(gateway, new FakeDecoder(), store, CLOCK)
                .ingest(COMMAND);

        assertThat(result.status()).isEqualTo(IngestionRunStatus.FAILED);
        assertThat(store.finish.code()).isEqualTo("RATE_LIMITED");
        assertThat(store.terminals).extracting(Terminal::status)
                .containsExactly(
                        IngestionItemStatus.PARTIAL,
                        IngestionItemStatus.SKIPPED,
                        IngestionItemStatus.SKIPPED);
        assertThat(events).noneMatch(event -> event.equals("gateway.detail.NA1_102")
                || event.equals("gateway.detail.NA1_103"));
    }

    @Test
    void sanitizesUnexpectedDecoderFailureAndContinues() {
        var events = new ArrayList<String>();
        var gateway = new FakeGateway(events, List.of("NA1_101", "NA1_102"));
        var store = new FakeStore(events);
        var decoder = new FakeDecoder();
        decoder.nextFailure = new IllegalStateException("{\"payload\":\"test-secret\"}");

        var result = new RiotIngestionService(gateway, decoder, store, CLOCK).ingest(COMMAND);

        assertThat(result.status()).isEqualTo(IngestionRunStatus.PARTIAL);
        assertThat(store.terminals.getFirst().status()).isEqualTo(IngestionItemStatus.FAILED);
        assertThat(store.terminals.getFirst().message())
                .isEqualTo("Match materialization failed")
                .doesNotContain("payload", "test-secret", "{");
        assertThat(store.terminals.get(1).status()).isEqualTo(IngestionItemStatus.COMPLETE);
    }

    @Test
    void zeroSuccessfulOrPartialMatchesFailsTheRun() {
        var events = new ArrayList<String>();
        var gateway = new FakeGateway(events, List.of("NA1_101", "NA1_102"));
        gateway.detailFailures.put("NA1_101", RiotFailureCode.NOT_FOUND);
        gateway.detailFailures.put("NA1_102", RiotFailureCode.NOT_FOUND);
        var store = new FakeStore(events);

        var result = new RiotIngestionService(gateway, new FakeDecoder(), store, CLOCK)
                .ingest(COMMAND);

        assertThat(result).isEqualTo(new RiotIngestionResult(RUN_ID, IngestionRunStatus.FAILED, 2, 0, 0, 2));
        assertThat(store.finish.code()).isEqualTo("NO_MATCHES_INGESTED");
    }

    @Test
    void setupCapturePersistenceFailureStopsBeforeAnotherGatewayRequest() {
        var events = new ArrayList<String>();
        var gateway = new FakeGateway(events, List.of("NA1_101"));
        var store = new FakeStore(events);
        store.captureFailureKind = SourceKind.ACCOUNT;

        var result = new RiotIngestionService(gateway, new FakeDecoder(), store, CLOCK)
                .ingest(COMMAND);

        assertThat(result.status()).isEqualTo(IngestionRunStatus.FAILED);
        assertThat(store.finish.code()).isEqualTo("SOURCE_PERSISTENCE_FAILED");
        assertThat(store.finish.message()).doesNotContain("private", "payload", "secret");
        assertThat(events).doesNotContain("gateway.list", "store.account");
    }

    @Test
    void matchCapturePersistenceFailureStopsRequestsAndSkipsRemainingItems() {
        var events = new ArrayList<String>();
        var gateway = new FakeGateway(events, List.of("NA1_101", "NA1_102", "NA1_103"));
        var store = new FakeStore(events);
        store.captureFailureKind = SourceKind.MATCH_DETAIL;

        var result = new RiotIngestionService(gateway, new FakeDecoder(), store, CLOCK)
                .ingest(COMMAND);

        assertThat(result).isEqualTo(
                new RiotIngestionResult(RUN_ID, IngestionRunStatus.FAILED, 3, 0, 0, 1));
        assertThat(store.finish.code()).isEqualTo("SOURCE_PERSISTENCE_FAILED");
        assertThat(store.terminals).extracting(Terminal::status)
                .containsExactly(
                        IngestionItemStatus.FAILED,
                        IngestionItemStatus.SKIPPED,
                        IngestionItemStatus.SKIPPED);
        assertThat(events).doesNotContain(
                "gateway.timeline.NA1_101",
                "gateway.detail.NA1_102",
                "gateway.detail.NA1_103");
    }

    @Test
    void materializationPersistenceFailureStopsInsteadOfFetchingAnotherMatch() {
        var events = new ArrayList<String>();
        var gateway = new FakeGateway(events, List.of("NA1_101", "NA1_102"));
        var store = new FakeStore(events);
        store.materializationFailure = true;

        var result = new RiotIngestionService(gateway, new FakeDecoder(), store, CLOCK)
                .ingest(COMMAND);

        assertThat(result).isEqualTo(
                new RiotIngestionResult(RUN_ID, IngestionRunStatus.FAILED, 2, 0, 0, 1));
        assertThat(store.finish.code()).isEqualTo("MATERIALIZATION_PERSISTENCE_FAILED");
        assertThat(events).doesNotContain("gateway.detail.NA1_102");
    }

    private static ProviderDocument document(SourceKind kind, String resource) {
        return new ProviderDocument(
                kind, resource, CLOCK.instant(), 200, "AMERICAS", "NA1", null,
                "a".repeat(64), 2, JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(), "test-v1", 1);
    }

    private static final class FakeGateway implements RiotGateway {
        private final List<String> events;
        private final List<String> matchIds;
        private final Map<String, RiotFailureCode> detailFailures = new HashMap<>();
        private final Map<String, RiotFailureCode> timelineFailures = new HashMap<>();
        private RiotFailureCode accountFailure;
        private RiotFailureCode listFailure;

        FakeGateway(List<String> events, List<String> matchIds) {
            this.events = events;
            this.matchIds = matchIds;
        }

        @Override
        public RiotAccountLookup resolveAccount(RiotId riotId) {
            events.add("gateway.account");
            fail(accountFailure);
            return new RiotAccountLookup(
                    new RiotAccount("invented-puuid", riotId.gameName(), riotId.tagLine()),
                    document(SourceKind.ACCOUNT, "invented-account"));
        }

        @Override
        public RiotMatchList listRankedMatchIds(String puuid, int count) {
            events.add("gateway.list");
            fail(listFailure);
            return new RiotMatchList(matchIds, document(SourceKind.MATCH_LIST, puuid));
        }

        @Override
        public ProviderDocument fetchMatchDetail(String matchId) {
            events.add("gateway.detail." + matchId);
            fail(detailFailures.get(matchId));
            return document(SourceKind.MATCH_DETAIL, matchId);
        }

        @Override
        public ProviderDocument fetchMatchTimeline(String matchId) {
            events.add("gateway.timeline." + matchId);
            fail(timelineFailures.get(matchId));
            return document(SourceKind.MATCH_TIMELINE, matchId);
        }

        private void fail(RiotFailureCode code) {
            if (code != null) {
                throw new RiotGatewayException(code, "sanitized gateway failure");
            }
        }
    }

    private static final class FakeStore implements RiotIngestionStore {
        private final List<String> events;
        private final List<Terminal> terminals = new ArrayList<>();
        private Finish finish;
        private SourceKind captureFailureKind;
        private boolean materializationFailure;

        FakeStore(List<String> events) {
            this.events = events;
        }

        public boolean isCompleteMatch(String matchId) { return false; }

        public void recordRetryNotBefore(UUID runId, Instant retryNotBefore) {}

        @Override
        public UUID startRun(RiotIngestionCommand command, Instant startedAt) {
            events.add("store.start");
            return RUN_ID;
        }

        @Override
        public CapturedDocument saveCapture(UUID runId, ProviderDocument document) {
            events.add("store.save." + document.kind());
            if (document.kind() == captureFailureKind) {
                captureFailureKind = null;
                throw new IllegalStateException("private payload secret");
            }
            return new CapturedDocument(UUID.randomUUID(), UUID.randomUUID(), document);
        }

        @Override
        public void recordResolvedAccount(UUID runId, RiotAccount account, CapturedDocument source) {
            events.add("store.account");
        }

        @Override
        public void addItems(UUID runId, List<String> matchIds) {
            events.add("store.items");
        }

        @Override
        public void markItemRunning(UUID runId, String matchId, Instant startedAt) {
            events.add("store.running." + matchId);
        }

        @Override
        public void materialize(UUID runId, String matchId, RiotMatchMaterialization materialization) {
            events.add("store.materialize." + matchId);
            if (materializationFailure) {
                materializationFailure = false;
                throw new IllegalStateException("private materialization detail");
            }
        }

        @Override
        public void markItemTerminal(
                UUID runId,
                String matchId,
                IngestionItemStatus status,
                String failureCode,
                String failureMessage,
                Instant completedAt) {
            events.add("store.terminal." + matchId + "." + status);
            terminals.add(new Terminal(matchId, status, failureCode, failureMessage));
        }

        @Override
        public void finishRun(
                UUID runId,
                IngestionRunStatus status,
                String failureCode,
                String failureMessage,
                Instant completedAt) {
            events.add("store.finish." + status);
            finish = new Finish(status, failureCode, failureMessage);
        }
    }

    private static final class FakeDecoder extends MatchV5Decoder {
        private int calls;
        private RuntimeException nextFailure;

        @Override
        public RiotMatchMaterialization decode(
                CapturedDocument detail,
                Optional<CapturedDocument> timeline) {
            calls++;
            if (nextFailure != null) {
                var failure = nextFailure;
                nextFailure = null;
                throw failure;
            }
            return null;
        }
    }

    private record Terminal(String matchId, IngestionItemStatus status, String code, String message) {}

    private record Finish(IngestionRunStatus status, String code, String message) {}
}
