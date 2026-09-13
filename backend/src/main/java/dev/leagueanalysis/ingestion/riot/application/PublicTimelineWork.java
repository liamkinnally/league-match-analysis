package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/** Upgrades existing detail evidence with one optional provider timeline. */
final class PublicTimelineWork implements PublicIngestionWork {
    private final UUID runId;
    private final String matchId;
    private final RiotGateway gateway;
    private final RiotIngestionStore store;
    private final PublicMatchLookupStore pages;
    private final MatchV5Decoder decoder;
    private final Clock clock;

    PublicTimelineWork(UUID runId, String matchId, RiotGateway gateway, RiotIngestionStore store,
            PublicMatchLookupStore pages, MatchV5Decoder decoder, Clock clock) {
        this.runId = runId; this.matchId = matchId; this.gateway = gateway; this.store = store;
        this.pages = pages; this.decoder = decoder; this.clock = clock;
    }

    public boolean step() {
        if (store.isExcludedMatch(matchId)) return finish(IngestionRunStatus.FAILED, "MATCH_UNAVAILABLE");
        if (store.isCompleteMatch(matchId)) return finish(IngestionRunStatus.COMPLETE, null);
        var detail = pages.storedDetail(matchId);
        if (detail.isEmpty() || store.isExcludedDocument(detail.get())) return finish(IngestionRunStatus.FAILED, "MATCH_UNAVAILABLE");
        store.markItemRunning(runId, matchId, clock.instant());
        try {
            // Fetch before saving the reused capture so a 429 retry cannot duplicate it.
            var timeline = gateway.fetchMatchTimeline(matchId);
            if (store.isExcludedDocument(timeline)) {
                store.discardExcludedMatch(runId, matchId);
                return finish(IngestionRunStatus.FAILED, "MATCH_UNAVAILABLE");
            }
            var detailCapture = store.saveCapture(runId, detail.get());
            var timelineCapture = store.saveCapture(runId, timeline);
            var facts = decoder.decode(detailCapture, Optional.of(timelineCapture));
            if (!facts.match().matchId().equals(matchId)
                    || !RiotIngestionCommand.supportsMatch(facts.match().queueId(), facts.match().mapId())) return finish(IngestionRunStatus.FAILED, "INVALID_RESPONSE");
            store.materialize(runId, matchId, facts);
            return finish(IngestionRunStatus.COMPLETE, null);
        } catch (RiotGatewayException failure) {
            if (failure.code() == RiotFailureCode.RATE_LIMITED) throw failure;
            return finish(IngestionRunStatus.FAILED, failure.code().name());
        }
    }

    private boolean finish(IngestionRunStatus status, String code) {
        store.markItemTerminal(runId, matchId, status == IngestionRunStatus.COMPLETE ? IngestionItemStatus.COMPLETE : IngestionItemStatus.FAILED,
                code, code == null ? null : "Timeline is unavailable", clock.instant());
        store.finishRun(runId, status, code, code == null ? null : "Timeline is unavailable", clock.instant());
        return true;
    }
}
