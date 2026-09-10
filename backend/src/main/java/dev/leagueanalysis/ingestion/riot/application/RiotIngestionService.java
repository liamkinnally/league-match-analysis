package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.domain.CapturedDocument;
import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.RiotId;
import dev.leagueanalysis.ingestion.riot.domain.RiotMatchMaterialization;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RiotIngestionService {
    private final RiotGateway gateway;
    private final MatchV5Decoder decoder;
    private final RiotIngestionStore store;
    private final Clock clock;

    public RiotIngestionService(
            RiotGateway gateway,
            MatchV5Decoder decoder,
            RiotIngestionStore store,
            Clock clock) {
        this.gateway = gateway;
        this.decoder = decoder;
        this.store = store;
        this.clock = clock;
    }

    public RiotIngestionResult ingest(RiotIngestionCommand command) {
        var runId = store.startRun(command, clock.instant());
        return execute(runId, command, gateway, false);
    }

    public RiotIngestionResult executePublic(UUID runId, RiotIngestionCommand command) {
        return execute(runId, command, gateway.forPublicLookup(), true);
    }

    private RiotIngestionResult execute(UUID runId, RiotIngestionCommand command, RiotGateway gateway, boolean publicLookup) {
        final RiotAccountLookup accountLookup;
        try {
            accountLookup = gateway.resolveAccount(new RiotId(command.gameName(), command.tagLine()));
        } catch (RiotGatewayException exception) {
            recordCooldown(runId, exception, publicLookup);
            return failSetup(runId, exception.code());
        } catch (RuntimeException exception) {
            return failUnexpectedSetup(runId);
        }

        try {
            var accountCapture = store.saveCapture(runId, accountLookup.source());
            store.recordResolvedAccount(runId, accountLookup.account(), accountCapture);
        } catch (RuntimeException exception) {
            return failPersistenceSetup(runId, "SOURCE_PERSISTENCE_FAILED",
                    "Source evidence persistence failed");
        }

        final RiotMatchList matchList;
        try {
            matchList = gateway.listRankedMatchIds(
                    accountLookup.account().puuid(), command.matchLimit());
        } catch (RiotGatewayException exception) {
            recordCooldown(runId, exception, publicLookup);
            return failSetup(runId, exception.code());
        } catch (RuntimeException exception) {
            return failUnexpectedSetup(runId);
        }

        final List<String> matchIds;
        try {
            store.saveCapture(runId, matchList.source());
            matchIds = matchList.matchIds();
            store.addItems(runId, matchIds);
        } catch (RuntimeException exception) {
            return failPersistenceSetup(runId, "SOURCE_PERSISTENCE_FAILED",
                    "Source evidence persistence failed");
        }

        if (publicLookup && matchIds.isEmpty()) {
            store.finishRun(runId, IngestionRunStatus.COMPLETE, null, null, clock.instant());
            return new RiotIngestionResult(runId, IngestionRunStatus.COMPLETE, 0, 0, 0, 0);
        }
        var complete = 0;
        var partial = 0;
        var failed = 0;
        for (var index = 0; index < matchIds.size(); index++) {
            var matchId = matchIds.get(index);
            store.markItemRunning(runId, matchId, clock.instant());
            if (publicLookup && store.isCompleteMatch(matchId)) {
                store.markItemTerminal(runId, matchId, IngestionItemStatus.COMPLETE, null, null, clock.instant());
                complete++;
                continue;
            }

            final ProviderDocument detailDocument;
            try {
                detailDocument = gateway.fetchMatchDetail(matchId);
            } catch (RiotGatewayException exception) {
                recordCooldown(runId, exception, publicLookup);
                store.markItemTerminal(
                        runId,
                        matchId,
                        IngestionItemStatus.FAILED,
                        exception.code().name(),
                        messageFor(exception.code()),
                        clock.instant());
                failed++;
                if (exception.code() == RiotFailureCode.RATE_LIMITED) {
                    skipRemaining(
                            runId,
                            matchIds,
                            index + 1,
                            RiotFailureCode.RATE_LIMITED.name(),
                            "Skipped after Riot rate limit");
                    return finishRateLimited(runId, matchIds.size(), complete, partial, failed);
                }
                continue;
            } catch (RuntimeException exception) {
                markUnexpectedFailure(runId, matchId);
                failed++;
                continue;
            }

            final CapturedDocument detail;
            try {
                detail = store.saveCapture(runId, detailDocument);
            } catch (RuntimeException exception) {
                return stopForPersistenceFailure(
                        runId,
                        matchIds,
                        index,
                        complete,
                        partial,
                        failed,
                        "SOURCE_PERSISTENCE_FAILED",
                        "Source evidence persistence failed");
            }

            Optional<CapturedDocument> timeline = Optional.empty();
            RiotGatewayException timelineFailure = null;
            ProviderDocument timelineDocument = null;
            try {
                timelineDocument = gateway.fetchMatchTimeline(matchId);
            } catch (RiotGatewayException exception) {
                recordCooldown(runId, exception, publicLookup);
                timelineFailure = exception;
                if (!canMaterializeDetailOnly(exception.code())) {
                    store.markItemTerminal(
                            runId,
                            matchId,
                            IngestionItemStatus.FAILED,
                            exception.code().name(),
                            messageFor(exception.code()),
                            clock.instant());
                    failed++;
                    if (exception.code() == RiotFailureCode.RATE_LIMITED) {
                        skipRemaining(
                                runId,
                                matchIds,
                                index + 1,
                                RiotFailureCode.RATE_LIMITED.name(),
                                "Skipped after Riot rate limit");
                        return finishRateLimited(runId, matchIds.size(), complete, partial, failed);
                    }
                    continue;
                }
            } catch (RuntimeException exception) {
                markUnexpectedFailure(runId, matchId);
                failed++;
                continue;
            }

            if (timelineDocument != null) {
                try {
                    timeline = Optional.of(store.saveCapture(runId, timelineDocument));
                } catch (RuntimeException exception) {
                    return stopForPersistenceFailure(
                            runId,
                            matchIds,
                            index,
                            complete,
                            partial,
                            failed,
                            "SOURCE_PERSISTENCE_FAILED",
                            "Source evidence persistence failed");
                }
            }

            final RiotMatchMaterialization materialization;
            try {
                materialization = decoder.decode(detail, timeline);
            } catch (RuntimeException exception) {
                markUnexpectedFailure(runId, matchId);
                failed++;
                continue;
            }

            try {
                store.materialize(runId, matchId, materialization);
            } catch (RuntimeException exception) {
                return stopForPersistenceFailure(
                        runId,
                        matchIds,
                        index,
                        complete,
                        partial,
                        failed,
                        "MATERIALIZATION_PERSISTENCE_FAILED",
                        "Match materialization persistence failed");
            }

            if (timelineFailure == null) {
                store.markItemTerminal(
                        runId, matchId, IngestionItemStatus.COMPLETE,
                        null, null, clock.instant());
                complete++;
            } else {
                store.markItemTerminal(
                        runId,
                        matchId,
                        IngestionItemStatus.PARTIAL,
                        timelineFailure.code().name(),
                        messageFor(timelineFailure.code()),
                        clock.instant());
                partial++;
                if (timelineFailure.code() == RiotFailureCode.RATE_LIMITED) {
                    skipRemaining(
                            runId,
                            matchIds,
                            index + 1,
                            RiotFailureCode.RATE_LIMITED.name(),
                            "Skipped after Riot rate limit");
                    return finishRateLimited(runId, matchIds.size(), complete, partial, failed);
                }
            }
        }

        return finishNormally(runId, matchIds.size(), complete, partial, failed);
    }

    private void recordCooldown(UUID runId, RiotGatewayException exception, boolean publicLookup) {
        if (publicLookup && exception.code() == RiotFailureCode.RATE_LIMITED) {
            store.recordRetryNotBefore(runId, exception.retryNotBefore() == null
                    ? clock.instant().plusSeconds(60) : exception.retryNotBefore());
        }
    }

    private RiotIngestionResult failSetup(UUID runId, RiotFailureCode code) {
        store.finishRun(
                runId,
                IngestionRunStatus.FAILED,
                code.name(),
                messageFor(code),
                clock.instant());
        return new RiotIngestionResult(runId, IngestionRunStatus.FAILED, 0, 0, 0, 0);
    }

    private RiotIngestionResult failUnexpectedSetup(UUID runId) {
        store.finishRun(
                runId,
                IngestionRunStatus.FAILED,
                RiotFailureCode.INVALID_RESPONSE.name(),
                "Ingestion setup failed",
                clock.instant());
        return new RiotIngestionResult(runId, IngestionRunStatus.FAILED, 0, 0, 0, 0);
    }

    private RiotIngestionResult failPersistenceSetup(
            UUID runId,
            String failureCode,
            String failureMessage) {
        store.finishRun(
                runId,
                IngestionRunStatus.FAILED,
                failureCode,
                failureMessage,
                clock.instant());
        return new RiotIngestionResult(runId, IngestionRunStatus.FAILED, 0, 0, 0, 0);
    }

    private RiotIngestionResult finishNormally(
            UUID runId,
            int requested,
            int complete,
            int partial,
            int failed) {
        final IngestionRunStatus status;
        final String failureCode;
        final String failureMessage;
        if (complete + partial == 0) {
            status = IngestionRunStatus.FAILED;
            failureCode = "NO_MATCHES_INGESTED";
            failureMessage = "No matches were ingested";
        } else if (partial > 0 || failed > 0) {
            status = IngestionRunStatus.PARTIAL;
            failureCode = "MATCHES_INCOMPLETE";
            failureMessage = "One or more matches were incomplete";
        } else {
            status = IngestionRunStatus.COMPLETE;
            failureCode = null;
            failureMessage = null;
        }
        store.finishRun(runId, status, failureCode, failureMessage, clock.instant());
        return new RiotIngestionResult(runId, status, requested, complete, partial, failed);
    }

    private RiotIngestionResult finishRateLimited(
            UUID runId,
            int requested,
            int complete,
            int partial,
            int failed) {
        store.finishRun(
                runId,
                IngestionRunStatus.FAILED,
                RiotFailureCode.RATE_LIMITED.name(),
                messageFor(RiotFailureCode.RATE_LIMITED),
                clock.instant());
        return new RiotIngestionResult(
                runId, IngestionRunStatus.FAILED, requested, complete, partial, failed);
    }

    private RiotIngestionResult stopForPersistenceFailure(
            UUID runId,
            List<String> matchIds,
            int currentIndex,
            int complete,
            int partial,
            int failed,
            String failureCode,
            String failureMessage) {
        store.markItemTerminal(
                runId,
                matchIds.get(currentIndex),
                IngestionItemStatus.FAILED,
                failureCode,
                failureMessage,
                clock.instant());
        skipRemaining(
                runId,
                matchIds,
                currentIndex + 1,
                failureCode,
                "Skipped after persistence failure");
        store.finishRun(
                runId,
                IngestionRunStatus.FAILED,
                failureCode,
                failureMessage,
                clock.instant());
        return new RiotIngestionResult(
                runId,
                IngestionRunStatus.FAILED,
                matchIds.size(),
                complete,
                partial,
                failed + 1);
    }

    private void skipRemaining(
            UUID runId,
            List<String> matchIds,
            int startIndex,
            String failureCode,
            String failureMessage) {
        for (var index = startIndex; index < matchIds.size(); index++) {
            store.markItemTerminal(
                    runId,
                    matchIds.get(index),
                    IngestionItemStatus.SKIPPED,
                    failureCode,
                    failureMessage,
                    clock.instant());
        }
    }

    private void markUnexpectedFailure(UUID runId, String matchId) {
        store.markItemTerminal(
                runId,
                matchId,
                IngestionItemStatus.FAILED,
                "MATERIALIZATION_FAILED",
                "Match materialization failed",
                clock.instant());
    }

    private boolean canMaterializeDetailOnly(RiotFailureCode code) {
        return code == RiotFailureCode.NOT_FOUND
                || code == RiotFailureCode.RATE_LIMITED
                || code == RiotFailureCode.UPSTREAM_UNAVAILABLE
                || code == RiotFailureCode.UPSTREAM_REJECTED
                || code == RiotFailureCode.NETWORK_FAILURE;
    }

    private String messageFor(RiotFailureCode code) {
        return switch (code) {
            case CONFIGURATION_MISSING -> "Riot API key is not configured";
            case INVALID_INPUT -> "Riot request input is invalid";
            case BODY_TOO_LARGE -> "Riot response exceeded the configured limit";
            case AUTHENTICATION_FAILED -> "Riot authentication failed";
            case NOT_FOUND -> "Riot resource was not found";
            case RATE_LIMITED -> "Riot rate limit was exceeded";
            case UPSTREAM_UNAVAILABLE -> "Riot service is unavailable";
            case UPSTREAM_REJECTED -> "Riot rejected the request";
            case NETWORK_FAILURE -> "Riot request failed";
            case INVALID_RESPONSE -> "Riot response is invalid";
        };
    }
}
