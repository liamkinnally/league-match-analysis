package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.domain.*;
import java.time.Clock;
import java.util.*;

/** Imports one summary per turn, retaining the provider list only after privacy checks. */
final class PublicHistoryWork implements PublicIngestionWork {
    private final UUID runId;
    private final RiotIngestionCommand command;
    private final RiotGateway gateway;
    private final RiotIngestionStore store;
    private final PublicMatchLookupStore pages;
    private final MatchV5Decoder decoder;
    private final Clock clock;
    private RiotAccount account;
    private RiotAccountLookup pendingAccount;
    private RiotMatchList list;
    private List<String> ids;
    private boolean retainList;
    private int index;
    private int failures;
    private int successes;
    private boolean done;

    PublicHistoryWork(UUID runId, RiotIngestionCommand command, RiotGateway gateway,
            RiotIngestionStore store, PublicMatchLookupStore pages, MatchV5Decoder decoder, Clock clock) {
        this.runId = runId; this.command = command; this.gateway = gateway; this.store = store;
        this.pages = pages; this.decoder = decoder; this.clock = clock;
    }

    public boolean step() {
        if (done) return true;
        try {
            if (account == null) return resolve();
            if (store.isExcludedAccount(account)) return fail("ACCOUNT_UNAVAILABLE");
            if (list == null) return list();
            if (index < ids.size()) return detail();
            if (retainList) store.saveCapture(runId, list.source());
            store.finishRun(runId, failures == 0 ? IngestionRunStatus.COMPLETE
                    : successes > 0 ? IngestionRunStatus.PARTIAL : IngestionRunStatus.FAILED,
                    failures == 0 ? null : "MATCHES_INCOMPLETE", failures == 0 ? null : "Some match data is unavailable", clock.instant());
            done = true;
            return true;
        } catch (RiotGatewayException failure) {
            if (failure.code() == RiotFailureCode.RATE_LIMITED) throw failure;
            return fail(failure.code().name());
        }
    }

    private boolean resolve() {
        boolean resolvedNow = pendingAccount == null;
        if (resolvedNow) pendingAccount = gateway.resolveAccount(new RiotId(command.gameName(), command.tagLine()));
        var response = pendingAccount;
        if (store.isExcludedAccount(response.account()) || store.isExcludedDocument(response.source())) {
            store.discardRun(runId);
            done = true;
            return true;
        }
        // Exclusion must take effect before yielding a known account back to the scheduler.
        // Recheck on the next turn as the exclusion ledger can change between provider calls.
        if (resolvedNow) return false;
        if (command.previousRunId() != null && !pages.matchesPageIdentity(command.previousRunId(), response.account().puuid()))
            return fail("ACCOUNT_CHANGED");
        var profile = gateway.verifyPlatformAccount(response.account().puuid());
        var capture = store.saveCapture(runId, response.source());
        store.recordResolvedAccount(runId, response.account(), capture);
        store.recordVerifiedRequestedIdentity(runId, command);
        if (profile != null) store.recordVerifiedProfile(runId, profile, clock.instant());
        account = response.account();
        return false;
    }

    private boolean list() {
        var response = gateway.listMatchIds(account.puuid(), command.queueId(), command.start(), command.matchLimit(), command.endTime());
        if (response.matchIds().size() > command.matchLimit()) return fail("INVALID_RESPONSE");
        ids = response.matchIds().stream().distinct().filter(id -> id.startsWith(command.platform() + "_"))
                .filter(id -> !store.isExcludedMatch(id)).toList();
        retainList = ids.size() == response.matchIds().size() && !store.isExcludedDocument(response.source());
        store.addItems(runId, ids);
        pages.recordPageSize(runId, response.matchIds().size());
        list = response;
        return false;
    }

    private boolean detail() {
        String id = ids.get(index);
        if (store.isExcludedMatch(id)) {
            store.discardExcludedMatch(runId, id);
            retainList = false;
            index++;
            return false;
        }
        store.markItemRunning(runId, id, clock.instant());
        if (pages.hasSummary(id, account.puuid(), command.queueId())) {
            store.enrichParticipantDetails(id);
            complete(id);
            return false;
        }
        try {
            var document = gateway.fetchMatchDetail(id);
            if (store.isExcludedDocument(document)) {
                store.discardExcludedMatch(runId, id);
                retainList = false;
                index++;
                return false;
            }
            if (command.queueId() == 0) {
                var payload = document.payload();
                var metadata = payload.get("metadata");
                var payloadMatchId = metadata == null ? null : metadata.get("matchId");
                if (document.kind() != SourceKind.MATCH_DETAIL || payloadMatchId == null
                        || !payloadMatchId.isString() || !id.equals(payloadMatchId.stringValue()))
                    throw new IllegalArgumentException("INVALID_MATCH_ID");
                var info = payload.get("info");
                var queue = info == null ? null : info.get("queueId");
                if (queue == null || !queue.isIntegralNumber() || !queue.canConvertToInt())
                    throw new IllegalArgumentException("INVALID_MATCH_QUEUE");
                if (!RiotIngestionCommand.SUPPORTED_QUEUES.contains(queue.intValue())) {
                    store.markItemTerminal(runId, id, IngestionItemStatus.SKIPPED,
                            "UNSUPPORTED_QUEUE", "Match queue is outside supported history", clock.instant());
                    index++;
                    return false;
                }
            }
            var detail = store.saveCapture(runId, document);
            var facts = decoder.decode(detail, Optional.empty());
            if (!id.equals(facts.match().matchId()) || (command.queueId() != 0 && facts.match().queueId() != command.queueId())
                    || !RiotIngestionCommand.supportsMatch(facts.match().queueId(), facts.match().mapId())
                    || facts.participants().stream().noneMatch(p -> p.puuid().equals(account.puuid())))
                throw new IllegalArgumentException("INVALID_MATCH_MEMBERSHIP");
            store.materialize(runId, id, facts);
            // A concurrent complete import may have been retained by the no-downgrade
            // guard. Do not claim success if its membership conflicts with this account.
            if (!pages.hasSummary(id, account.puuid(), command.queueId())) {
                failed(id, "MATCH_MEMBERSHIP_CONFLICT");
            } else complete(id);
        } catch (RiotGatewayException failure) {
            if (failure.code() == RiotFailureCode.RATE_LIMITED) throw failure;
            failed(id, failure.code().name());
        } catch (RuntimeException failure) {
            failed(id, "MATERIALIZATION_FAILED");
        }
        return false;
    }

    private void complete(String id) {
        store.markItemTerminal(runId, id, IngestionItemStatus.COMPLETE, null, null, clock.instant());
        successes++; index++;
    }
    private void failed(String id, String code) {
        store.markItemTerminal(runId, id, IngestionItemStatus.FAILED, code, "Match summary is unavailable", clock.instant());
        failures++; index++;
    }
    private boolean fail(String code) {
        store.finishRun(runId, IngestionRunStatus.FAILED, code, "History lookup could not finish", clock.instant());
        done = true;
        return true;
    }
}
