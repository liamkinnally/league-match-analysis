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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;

class PublicLookupCacheIdentityTest {
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T20:30:00Z"), ZoneOffset.UTC);
    private static final RiotIngestionCommand COMMAND = new RiotIngestionCommand("KitingInYourLane", "000", 5);

    @Test
    void publicLookupRefetchesCompleteCachedMatchWhenResolvedPuuidIsMissing() {
        var gateway = new FakeGateway();
        var store = new FakeStore();
        var service = new RiotIngestionService(gateway, new FakeDecoder(), store, CLOCK);

        service.executePublic(RUN_ID, COMMAND);

        assertThat(gateway.detailCalls).isEqualTo(1);
        assertThat(store.materializeCalls).isEqualTo(1);
    }

    private static ProviderDocument document(SourceKind kind, String resource) {
        return new ProviderDocument(
                kind, resource, CLOCK.instant(), 200, "AMERICAS", "NA1", null,
                "a".repeat(64), 2, JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(), "test-v1", 1);
    }

    private static final class FakeGateway implements RiotGateway {
        private int detailCalls;

        @Override
        public RiotAccountLookup resolveAccount(RiotId riotId) {
            return new RiotAccountLookup(
                    new RiotAccount("current-puuid", riotId.gameName(), riotId.tagLine()),
                    document(SourceKind.ACCOUNT, "account"));
        }

        @Override
        public RiotMatchList listRankedMatchIds(String puuid, int count) {
            return new RiotMatchList(List.of("NA1_101"), document(SourceKind.MATCH_LIST, puuid));
        }

        @Override
        public ProviderDocument fetchMatchDetail(String matchId) {
            detailCalls++;
            return document(SourceKind.MATCH_DETAIL, matchId);
        }

        @Override
        public ProviderDocument fetchMatchTimeline(String matchId) {
            return document(SourceKind.MATCH_TIMELINE, matchId);
        }
    }

    private static final class FakeDecoder extends MatchV5Decoder {
        @Override
        public RiotMatchMaterialization decode(CapturedDocument detail, java.util.Optional<CapturedDocument> timeline) {
            return null;
        }
    }

    private static final class FakeStore implements RiotIngestionStore {
        private int materializeCalls;

        @Override
        public UUID startRun(RiotIngestionCommand command, Instant startedAt) {
            return RUN_ID;
        }

        @Override
        public boolean isCompleteMatch(String matchId) {
            return true;
        }

        // This overload becomes the cache predicate once the production contract is made identity-aware.
        public boolean isCompleteMatch(String matchId, String puuid) {
            return "old-puuid".equals(puuid);
        }

        @Override
        public void recordRetryNotBefore(UUID runId, Instant retryNotBefore) {}

        @Override
        public CapturedDocument saveCapture(UUID runId, ProviderDocument document) {
            return new CapturedDocument(UUID.randomUUID(), UUID.randomUUID(), document);
        }

        @Override
        public void recordResolvedAccount(UUID runId, RiotAccount account, CapturedDocument source) {}

        @Override
        public void addItems(UUID runId, List<String> matchIds) {}

        @Override
        public void markItemRunning(UUID runId, String matchId, Instant startedAt) {}

        @Override
        public void materialize(UUID runId, String matchId, RiotMatchMaterialization materialization) {
            materializeCalls++;
        }

        @Override
        public void markItemTerminal(UUID runId, String matchId, IngestionItemStatus status,
                String failureCode, String failureMessage, Instant completedAt) {}

        @Override
        public void finishRun(UUID runId, IngestionRunStatus status,
                String failureCode, String failureMessage, Instant completedAt) {}
    }
}
