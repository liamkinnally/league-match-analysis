package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.domain.CapturedDocument;
import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.RiotAccount;
import dev.leagueanalysis.ingestion.riot.domain.RiotId;
import dev.leagueanalysis.ingestion.riot.domain.RiotMatchMaterialization;
import dev.leagueanalysis.ingestion.riot.domain.SourceKind;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicMatchLookupServiceTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);
    final PublicMatchLookupStore store = mock(PublicMatchLookupStore.class);
    final RiotIngestionService ingestion = mock(RiotIngestionService.class);
    final Queue<Runnable> work = new ArrayDeque<>();
    final PublicMatchLookupService service = new PublicMatchLookupService(ingestion, store, clock, true, work::add);

    PublicMatchLookupServiceTest() {
        when(ingestion.historyWork(any(), any(), any())).thenReturn(() -> true);
        when(store.latestCooldown()).thenReturn(Optional.empty());
        when(store.findFresh(any(), any())).thenReturn(Optional.empty());
        when(store.startPublicRun(any(), any())).thenAnswer(call -> {
            UUID id = UUID.randomUUID();
            var command = (RiotIngestionCommand) call.getArgument(0);
            when(store.readPublicRun(id)).thenReturn(Optional.of(new PublicMatchLookup(id,
                    command.gameName(), command.tagLine(), "RUNNING", null, null, List.of())));
            return id;
        });
    }

    @Test void explicitRecentRecordUsesSoloQueueBoundedPageAndAccountCooldown() {
        UUID original=UUID.randomUUID();
        when(store.readPageCommand(original)).thenReturn(Optional.of(new RiotIngestionCommand("Invented","NA1",20,0,0,clock.instant().getEpochSecond(),null)));
        when(store.latestRefresh(any())).thenReturn(Optional.of(clock.instant().minusSeconds(899)));
        assertThatThrownBy(()->service.recentRecord(original,"peer")).isInstanceOfSatisfying(PublicLookupException.class,e->assertThat(e.status()).isEqualTo(429));
        assertThat(work).isEmpty();
        when(store.latestRefresh(any())).thenReturn(Optional.empty());
        var result=service.recentRecord(original,"peer");
        assertThat(service.recentRecord(original,"peer").lookup().runId()).isEqualTo(result.lookup().runId());
        assertThat(work).hasSize(1);
        verify(ingestion).historyWork(eq(result.lookup().runId()),argThat(c->c.queueId()==420&&c.matchLimit()==20&&c.start()==0&&c.endTime().equals(clock.instant().getEpochSecond())),eq(store));
    }

    @Test void returnsRunBeforeWorkAndSharesIdenticalActiveRequests() {
        var first = service.submit(" Invented ", "NA1", "peer");
        var second = service.submit("invented", "na1", "peer");
        assertThat(first.httpStatus()).isEqualTo(202);
        assertThat(second.lookup().runId()).isEqualTo(first.lookup().runId());
        assertThat(work).hasSize(1);
        verify(ingestion, never()).executePublic(any(), any());
        work.remove().run();
        verify(ingestion).historyWork(eq(first.lookup().runId()), argThat(c -> c.matchLimit() == 20 && c.queueId() == 0), eq(store));
    }

    @Test void allowsOnlyOneWorkerAndFourWaitingAndReleasesFinishedSlot() {
        for (int i = 0; i < 5; i++) assertThat(service.submit("Player" + i, "NA1", "peer" + i).httpStatus()).isEqualTo(202);
        assertThatThrownBy(() -> service.submit("Sixth", "NA1", "sixth"))
                .isInstanceOfSatisfying(PublicLookupException.class, e -> assertThat(e.status()).isEqualTo(429));
        work.remove().run();
        assertThat(service.submit("Sixth", "NA1", "sixth").httpStatus()).isEqualTo(202);
    }

    @Test void servesCachedHistoryDuringCooldownAndWhenLiveLookupIsDisabled() {
        UUID id = UUID.randomUUID();
        var empty = new PublicMatchLookup(id, "Nobody", "NA1", "EMPTY", null, null, List.of());
        when(store.findFresh(any(), eq(Instant.EPOCH))).thenReturn(Optional.of(empty));
        assertThat(service.submit("Nobody", "NA1", "peer").httpStatus()).isEqualTo(200);
        assertThat(work).isEmpty();
        when(store.latestCooldown()).thenReturn(Optional.of(clock.instant().plusSeconds(120)));
        assertThat(service.submit("Nobody", "NA1", "peer").httpStatus()).isEqualTo(200);
        var disabled = new PublicMatchLookupService(ingestion, store, clock, false, work::add);
        assertThat(disabled.submit("Nobody", "NA1", "peer").httpStatus()).isEqualTo(200);
        disabled.close();
    }

    @Test void boundsNewSubmissionsPerSocketPeerAndDisabledLookupDoesNoWork() {
        for (int i = 0; i < 6; i++) { service.submit("Player" + i, "NA1", "peer"); work.remove().run(); }
        assertThatThrownBy(() -> service.submit("Seventh", "NA1", "peer"))
                .isInstanceOf(PublicLookupException.class);
        var disabled = new PublicMatchLookupService(ingestion, store, clock, false, work::add);
        assertThatThrownBy(() -> disabled.submit("Player", "NA1", "other"))
                .isInstanceOfSatisfying(PublicLookupException.class, e -> assertThat(e.status()).isEqualTo(503));
    }

    @Test void distinctNameAndTagPairsCannotCollideInActiveDeduplication() {
        var first = service.submit("A#B", "C", "peer");
        var second = service.submit("A", "B#C", "peer");
        assertThat(second.lookup().runId()).isNotEqualTo(first.lookup().runId());
    }

    @Test void rejectsControlCharactersBeforeCreatingWork() {
        assertThatThrownBy(() -> service.submit("Bad\u0000Name", "NA1", "peer"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(work).isEmpty();
    }

    @Test void publicIngestionRefetchesCompleteCachedMatchWhenResolvedPuuidIsMissing() {
        var gateway = new CacheGateway();
        var cacheStore = new CacheStore();
        var decoder = new CacheDecoder();
        var ingestionService = new RiotIngestionService(gateway, decoder, cacheStore, clock);

        ingestionService.executePublic(UUID.randomUUID(), new RiotIngestionCommand("ExamplePlayer", "NA1", 5));

        assertThat(gateway.detailCalls).isEqualTo(1);
        assertThat(cacheStore.materializeCalls).isEqualTo(1);
    }

    @Test void accountsInterleaveOneTurnAtATime() {
        var turns = new ArrayList<String>();
        when(ingestion.historyWork(any(), any(), any())).thenAnswer(call -> {
            String name = ((RiotIngestionCommand) call.getArgument(1)).gameName();
            var count = new java.util.concurrent.atomic.AtomicInteger();
            return (PublicIngestionWork) () -> { turns.add(name); return count.incrementAndGet() == 3; };
        });
        service.submit("First", "NA1", "peer");
        service.submit("Second", "NA1", "peer");
        while (!work.isEmpty()) work.remove().run();
        assertThat(turns).containsExactly("First", "Second", "First", "Second", "First", "Second");
    }

    @Test void rateLimitedTurnYieldsAndResumesAtRetryTimeWithoutBusyLoop() {
        var now = new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
        var mutableClock = mock(Clock.class);
        when(mutableClock.instant()).thenAnswer(call -> now.get());
        var delayed = new ArrayList<Runnable>();
        var delays = new ArrayList<Duration>();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(ingestion.historyWork(any(), any(), any())).thenReturn(() -> {
            if (attempts.incrementAndGet() == 1) throw new RiotGatewayException(RiotFailureCode.RATE_LIMITED,
                    "sanitized", now.get().plusSeconds(120));
            return true;
        });
        var paced = new PublicMatchLookupService(ingestion, store, mutableClock, true, work::add,
                (task, delay) -> { delayed.add(task); delays.add(delay); });
        var id = paced.submit("Limited", "NA1", "peer").lookup().runId();
        work.remove().run();
        assertThat(work).isEmpty();
        assertThat(attempts).hasValue(1);
        assertThat(delays).containsExactly(Duration.ofSeconds(120));
        verify(ingestion).recordPublicCooldown(id, now.get().plusSeconds(120));
        now.set(now.get().plusSeconds(120));
        delayed.removeFirst().run();
        assertThat(attempts).hasValue(2);
        verify(store, never()).failPublicRun(eq(id), any());
        paced.close();
    }

    @Test void startupFailsInterruptedPublicRunsAndRetainsRows() {
        service.interruptPreviousRuns();
        verify(store).failInterrupted(clock.instant());
    }

    private ProviderDocument document(SourceKind kind, String resource) {
        return new ProviderDocument(
                kind, resource, clock.instant(), 200, "AMERICAS", "NA1", null,
                "a".repeat(64), 2, JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(), "test-v1", 1);
    }

    private final class CacheGateway implements RiotGateway {
        int detailCalls;

        @Override public RiotAccountLookup resolveAccount(RiotId riotId) {
            return new RiotAccountLookup(
                    new RiotAccount("current-puuid", riotId.gameName(), riotId.tagLine()),
                    document(SourceKind.ACCOUNT, "account"));
        }

        @Override public RiotMatchList listRankedMatchIds(String puuid, int count) {
            return new RiotMatchList(List.of("NA1_101"), document(SourceKind.MATCH_LIST, puuid));
        }

        @Override public ProviderDocument fetchMatchDetail(String matchId) {
            detailCalls++;
            return document(SourceKind.MATCH_DETAIL, matchId);
        }

        @Override public ProviderDocument fetchMatchTimeline(String matchId) {
            return document(SourceKind.MATCH_TIMELINE, matchId);
        }
    }

    private static final class CacheDecoder extends MatchV5Decoder {
        @Override public RiotMatchMaterialization decode(
                CapturedDocument detail, Optional<CapturedDocument> timeline) {
            return null;
        }
    }

    private static final class CacheStore implements RiotIngestionStore {
        int materializeCalls;

        @Override public UUID startRun(RiotIngestionCommand command, Instant startedAt) {
            return UUID.randomUUID();
        }

        @Override public boolean isCompleteMatch(String matchId) {
            return true;
        }

        public boolean isCompleteMatch(String matchId, String puuid) {
            return "old-puuid".equals(puuid);
        }

        @Override public void recordRetryNotBefore(UUID runId, Instant retryNotBefore) {}

        @Override public CapturedDocument saveCapture(UUID runId, ProviderDocument document) {
            return new CapturedDocument(UUID.randomUUID(), UUID.randomUUID(), document);
        }

        @Override public void recordResolvedAccount(UUID runId, RiotAccount account, CapturedDocument source) {}
        @Override public void addItems(UUID runId, List<String> matchIds) {}
        @Override public void markItemRunning(UUID runId, String matchId, Instant startedAt) {}

        @Override public void materialize(UUID runId, String matchId, RiotMatchMaterialization materialization) {
            materializeCalls++;
        }

        @Override public void markItemTerminal(UUID runId, String matchId, IngestionItemStatus status,
                String failureCode, String failureMessage, Instant completedAt) {}

        @Override public void finishRun(UUID runId, IngestionRunStatus status,
                String failureCode, String failureMessage, Instant completedAt) {}
    }
}
