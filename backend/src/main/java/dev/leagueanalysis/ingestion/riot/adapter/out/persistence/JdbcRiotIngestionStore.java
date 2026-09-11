package dev.leagueanalysis.ingestion.riot.adapter.out.persistence;

import dev.leagueanalysis.privacy.PrivacyHash;
import dev.leagueanalysis.privacy.PrivacyRuntimeGuard;
import dev.leagueanalysis.ingestion.riot.application.IngestionItemStatus;
import dev.leagueanalysis.ingestion.riot.application.IngestionRunStatus;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionCommand;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionStore;
import dev.leagueanalysis.ingestion.riot.domain.CapturedDocument;
import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.RiotAccount;
import dev.leagueanalysis.ingestion.riot.domain.RiotMatchMaterialization;
import dev.leagueanalysis.ingestion.riot.domain.SourceKind;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import dev.leagueanalysis.ingestion.riot.application.PublicMatchLookup;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Repository
@org.springframework.context.annotation.DependsOn("privacyRuntimeGuard")
public class JdbcRiotIngestionStore implements RiotIngestionStore, dev.leagueanalysis.ingestion.riot.application.PublicMatchLookupStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private PrivacyRuntimeGuard privacyRuntimeGuard;

    @org.springframework.beans.factory.annotation.Autowired
    public void setPrivacyRuntimeGuard(PrivacyRuntimeGuard guard) { this.privacyRuntimeGuard = guard; }

    private void requireHealthy() {
        if (privacyRuntimeGuard != null) privacyRuntimeGuard.requireHealthy();
    }

    public JdbcRiotIngestionStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
    }

    @Override
    public boolean isExcludedAccount(RiotAccount account) {
        requireHealthy();
        // Riot IDs can be reassigned. Only the verified stable PUUID determines
        // whether this account is the excluded person.
        return excludedHash("puuid", PrivacyHash.of(account.puuid()));
    }

    @Override
    public boolean isExcludedMatch(String matchId) {
        requireHealthy();
        return excludedHash("match", PrivacyHash.of(matchId));
    }

    private boolean excludedHash(String kind, String hash) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from league_analysis.privacy_exclusion
                    where kind = ? and subject_hash = ?)
                """, Boolean.class, kind, hash));
    }

    @Override
    public boolean isExcludedDocument(ProviderDocument document) {
        requireHealthy();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select league_analysis.privacy_json_excluded(cast(? as jsonb))
                    or league_analysis.privacy_json_excluded(cast(? as jsonb))
                    or league_analysis.privacy_blocked('puuid', ?)
                    or league_analysis.privacy_blocked('match', ?)
                """, Boolean.class, document.payload().toString(), document.responseMetadata().toString(),
                document.resourceKey(), document.resourceKey()));
    }

    @Override
    public void discardRun(UUID runId) {
        requireHealthy();
        // Called before persisting an excluded account response. Do not risk deleting
        // other evidence if a future caller mistakenly uses this after setup.
        int removed = jdbc.update("""
                delete from league_analysis.ingestion_run r where r.id = ? and r.resolved_puuid is null
                    and not exists(select 1 from league_analysis.source_capture c where c.ingestion_run_id = r.id)
                    and not exists(select 1 from league_analysis.ingestion_item i where i.ingestion_run_id = r.id)
                """, runId);
        if (removed != 1) throw new IllegalStateException("Unresolved lookup discard failed");
    }

    @Override
    public void discardExcludedMatch(UUID runId, String matchId) {
        requireHealthy();
        transactions.executeWithoutResult(status -> {
            var payloadIds = jdbc.query("""
                    select source_payload_id from league_analysis.source_capture
                    where ingestion_run_id = ? and resource_key = ?
                        and source_kind in ('MATCH_DETAIL', 'MATCH_TIMELINE')
                    """, (rs, row) -> rs.getObject(1, UUID.class), runId, matchId);
            jdbc.update("delete from league_analysis.ingestion_item where ingestion_run_id = ? and match_id = ?",
                    runId, matchId);
            jdbc.update("""
                    delete from league_analysis.source_capture where ingestion_run_id = ? and resource_key = ?
                        and source_kind in ('MATCH_DETAIL', 'MATCH_TIMELINE')
                    """, runId, matchId);
            for (var payloadId : payloadIds) {
                jdbc.update("""
                        delete from league_analysis.source_payload p where id = ?
                            and not exists(select 1 from league_analysis.source_capture c where c.source_payload_id = p.id)
                        """, payloadId);
            }
            // The association was just verified from the provider document. Retaining
            // only its hash also lets later match-list responses be rejected before capture.
            jdbc.update("""
                    insert into league_analysis.privacy_exclusion(kind, subject_hash) values ('match', ?)
                    on conflict do nothing
                    """, PrivacyHash.of(matchId));
        });
    }

    @Override
    public UUID startRun(RiotIngestionCommand command, Instant startedAt) {
        requireHealthy();
        var runId = UUID.randomUUID();
        jdbc.update("""
                insert into league_analysis.ingestion_run
                    (id, requested_game_name, requested_tag_line, platform_route,
                     regional_route, queue_id, match_limit, status, started_at)
                values (?, '', '', 'NA1', 'AMERICAS', 420, ?, 'RUNNING', ?)
                """, runId, command.matchLimit(), timestamp(startedAt));
        return runId;
    }

    @Override
    public UUID startPublicRun(RiotIngestionCommand command, Instant now) {
        requireHealthy();
        return Objects.requireNonNull(transactions.execute(status -> {
            var id = startRun(command, now);
            jdbc.update("update league_analysis.ingestion_run set public_request = true where id = ?", id);
            return id;
        }));
    }

    @Override
    public boolean isCompleteMatch(String matchId) {
        requireHealthy();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from league_analysis.riot_match
                where match_id = ? and queue_id = 420 and timeline_source_capture_id is not null)
                """, Boolean.class, matchId));
    }

    @Override
    public void recordRetryNotBefore(UUID runId, Instant retryNotBefore) {
        requireHealthy();
        jdbc.update("""
                update league_analysis.ingestion_run set retry_not_before = greatest(retry_not_before, ?)
                where id = ?
                """, timestamp(retryNotBefore), runId);
    }

    @Override
    public Optional<Instant> latestCooldown() {
        requireHealthy();
        var value = jdbc.queryForObject("""
                select max(retry_not_before) from league_analysis.ingestion_run where public_request
                """, OffsetDateTime.class);
        return Optional.ofNullable(value).map(OffsetDateTime::toInstant);
    }

    @Override
    public Optional<PublicMatchLookup> findFresh(RiotIngestionCommand command, Instant since) {
        requireHealthy();
        return jdbc.query("""
                select id from league_analysis.ingestion_run
                where public_request and status = 'COMPLETE' and completed_at > ?
                    and lower(requested_game_name) = lower(?) and lower(requested_tag_line) = lower(?)
                order by completed_at desc limit 1
                """, (rs, row) -> rs.getObject("id", UUID.class), timestamp(since),
                command.gameName(), command.tagLine()).stream().findFirst().flatMap(this::readPublicRun);
    }

    @Override
    public Optional<PublicMatchLookup> readPublicRun(UUID runId) {
        requireHealthy();
        return jdbc.query("""
                select id, requested_game_name, requested_tag_line, status, failure_code, retry_not_before
                from league_analysis.ingestion_run r where id = ? and public_request
                """, (rs, row) -> {
                    String status = rs.getString("status");
                    var matches = publicMatches(runId);
                    if ("COMPLETE".equals(status) && matches.isEmpty()) status = "EMPTY";
                    var retry = rs.getObject("retry_not_before", OffsetDateTime.class);
                    return new PublicMatchLookup(runId, rs.getString("requested_game_name"),
                            rs.getString("requested_tag_line"), status, publicMessage(rs.getString("failure_code")),
                            retry == null ? null : retry.toInstant(), matches);
                }, runId).stream().findFirst();
    }

    private List<PublicMatchLookup.MatchSummary> publicMatches(UUID runId) {
        return jdbc.query("""
                select m.match_id, p.participant_id, p.champion_name, p.champion_id, m.game_version, p.end_item_ids::text, p.team_position,
                    p.win, coalesce(m.game_start_ms, m.game_creation_ms) started_at_ms, m.game_duration_seconds,
                    p.kills, p.deaths, p.assists, p.total_minions_killed + p.neutral_minions_killed cs,
                    p.gold_earned, m.timeline_source_capture_id is not null timeline_available
                from league_analysis.ingestion_run r
                join league_analysis.ingestion_item i on i.ingestion_run_id = r.id
                join league_analysis.riot_match m on m.match_id = i.match_id and m.queue_id = 420
                join league_analysis.riot_participant p on p.match_id = m.match_id and p.puuid = r.resolved_puuid
                where r.id = ? and r.public_request and i.status in ('COMPLETE', 'PARTIAL')
                order by i.ordinal limit 5
                """, (rs, row) -> new PublicMatchLookup.MatchSummary(rs.getString("match_id"),
                rs.getInt("participant_id"), rs.getString("champion_name"), rs.getInt("champion_id"),
                rs.getString("game_version"), itemIds(rs.getString("end_item_ids")),
                rs.getString("team_position"), rs.getBoolean("win"), rs.getLong("started_at_ms"),
                rs.getLong("game_duration_seconds"), rs.getInt("kills"), rs.getInt("deaths"),
                rs.getInt("assists"), rs.getInt("cs"), rs.getInt("gold_earned"),
                rs.getBoolean("timeline_available")), runId);
    }

    private List<Integer> itemIds(String encoded) {
        var items = new java.util.ArrayList<Integer>();
        for (var node : json.readTree(encoded)) items.add(node.intValue());
        return List.copyOf(items);
    }

    private String publicMessage(String code) {
        if (code == null) return null;
        return switch (code) {
            case "AUTHENTICATION_FAILED", "CONFIGURATION_MISSING" -> "Live lookup is unavailable. Explore the sample match.";
            case "NOT_FOUND" -> "Riot ID was not found. Check the name and tag.";
            case "RATE_LIMITED" -> "Riot is cooling down. Try again after the indicated time.";
            case "INTERRUPTED" -> "Lookup was interrupted. Search again to retry.";
            case "MATCHES_INCOMPLETE" -> "Some match data is unavailable. Completed matches are ready to open.";
            default -> "Lookup could not finish. Search again or explore the sample match.";
        };
    }

    @Override
    public void failInterrupted(Instant now) {
        requireHealthy();
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    update league_analysis.ingestion_item set status = 'SKIPPED', failure_code = 'INTERRUPTED',
                        failure_message = 'Lookup interrupted', completed_at = ?
                    where status in ('PENDING', 'RUNNING') and ingestion_run_id in
                        (select id from league_analysis.ingestion_run where public_request and status = 'RUNNING')
                    """, timestamp(now));
            jdbc.update("""
                    update league_analysis.ingestion_run set status = 'FAILED', failure_code = 'INTERRUPTED',
                        failure_message = 'Lookup interrupted', completed_at = ?
                    where public_request and status = 'RUNNING'
                    """, timestamp(now));
        });
    }

    @Override
    public void stopForCooldown(UUID runId, Instant retryNotBefore, Instant now) {
        requireHealthy();
        transactions.executeWithoutResult(status -> {
            recordRetryNotBefore(runId, retryNotBefore);
            finishRun(runId, IngestionRunStatus.FAILED, "RATE_LIMITED", "Riot rate limit was exceeded", now);
        });
    }

    @Override
    public void failPublicRun(UUID runId, Instant now) {
        requireHealthy();
        finishRun(runId, IngestionRunStatus.FAILED, "INTERRUPTED", "Lookup interrupted", now);
    }

    @Override
    public CapturedDocument saveCapture(UUID runId, ProviderDocument document) {
        requireHealthy();
        return Objects.requireNonNull(transactions.execute(status -> {
            var candidatePayloadId = UUID.randomUUID();
            var payloadId = jdbc.queryForObject("""
                    insert into league_analysis.source_payload
                        (id, source_kind, body_sha256, body_size_bytes, payload_json)
                    values (?, ?, ?, ?, cast(? as jsonb))
                    on conflict (source_kind, body_sha256)
                    do update set payload_json = excluded.payload_json
                    returning id
                    """, UUID.class,
                    candidatePayloadId,
                    document.kind().name(),
                    document.bodySha256(),
                    document.bodySizeBytes(),
                    document.payload().toString());
            var captureId = UUID.randomUUID();
            jdbc.update("""
                    insert into league_analysis.source_capture
                        (id, ingestion_run_id, source_payload_id, source_kind, resource_key,
                         regional_route, platform_route, captured_at, http_status,
                         provider_game_version, response_metadata, parser_version, attempt)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                    """,
                    captureId,
                    runId,
                    payloadId,
                    document.kind().name(),
                    document.resourceKey(),
                    document.regionalRoute(),
                    document.platformRoute(),
                    timestamp(document.capturedAt()),
                    document.httpStatus(),
                    document.providerGameVersion(),
                    document.responseMetadata().toString(),
                    document.parserVersion(),
                    document.attempt());
            attachMatchCapture(runId, document, captureId);
            return new CapturedDocument(payloadId, captureId, document);
        }));
    }

    @Override
    public void recordVerifiedRequestedIdentity(UUID runId, RiotIngestionCommand command) {
        requireHealthy();
        int updated = jdbc.update("""
                update league_analysis.ingestion_run r set requested_game_name = ?, requested_tag_line = ?
                where id = ? and resolved_puuid is not null
                    and exists(select 1 from league_analysis.source_capture c
                        where c.ingestion_run_id = r.id and c.source_kind = 'ACCOUNT')
                """, command.gameName(), command.tagLine(), runId);
        if (updated != 1) throw new IllegalStateException("Verified lookup identity unavailable");
    }

    @Override
    public void recordResolvedAccount(UUID runId, RiotAccount account, CapturedDocument source) {
        requireHealthy();
        requireCaptureInRun(runId, source);
        jdbc.update("update league_analysis.ingestion_run set resolved_puuid = ? where id = ?", account.puuid(), runId);
        jdbc.update("""
                insert into league_analysis.riot_identity
                    (puuid, game_name, tag_line, platform_route, first_observed_at,
                     last_observed_at, last_source_capture_id)
                values (?, ?, ?, 'NA1', ?, ?, ?)
                on conflict (puuid) do update set
                    game_name = case
                        when excluded.last_observed_at >= league_analysis.riot_identity.last_observed_at
                            then excluded.game_name
                        else league_analysis.riot_identity.game_name
                    end,
                    tag_line = case
                        when excluded.last_observed_at >= league_analysis.riot_identity.last_observed_at
                            then excluded.tag_line
                        else league_analysis.riot_identity.tag_line
                    end,
                    platform_route = case
                        when excluded.last_observed_at >= league_analysis.riot_identity.last_observed_at
                            then excluded.platform_route
                        else league_analysis.riot_identity.platform_route
                    end,
                    first_observed_at = least(
                        league_analysis.riot_identity.first_observed_at,
                        excluded.first_observed_at),
                    last_observed_at = greatest(
                        league_analysis.riot_identity.last_observed_at,
                        excluded.last_observed_at),
                    last_source_capture_id = case
                        when excluded.last_observed_at >= league_analysis.riot_identity.last_observed_at
                            then excluded.last_source_capture_id
                        else league_analysis.riot_identity.last_source_capture_id
                    end
                """,
                account.puuid(),
                account.gameName(),
                account.tagLine(),
                timestamp(source.document().capturedAt()),
                timestamp(source.document().capturedAt()),
                source.captureId());
    }

    @Override
    public void addItems(UUID runId, List<String> matchIds) {
        requireHealthy();
        transactions.executeWithoutResult(status -> {
            for (var ordinal = 0; ordinal < matchIds.size(); ordinal++) {
                jdbc.update("""
                        insert into league_analysis.ingestion_item
                            (ingestion_run_id, match_id, ordinal, status)
                        values (?, ?, ?, 'PENDING')
                        on conflict (ingestion_run_id, match_id) do nothing
                        """, runId, matchIds.get(ordinal), ordinal);
            }
        });
    }

    @Override
    public void markItemRunning(UUID runId, String matchId, Instant startedAt) {
        requireHealthy();
        jdbc.update("""
                update league_analysis.ingestion_item
                set status = 'RUNNING', failure_code = null, failure_message = null,
                    started_at = ?, completed_at = null
                where ingestion_run_id = ? and match_id = ?
                """, timestamp(startedAt), runId, matchId);
    }

    @Override
    public void materialize(UUID runId, String matchId, RiotMatchMaterialization materialization) {
        requireHealthy();
        if (!matchId.equals(materialization.match().matchId())) {
            throw new IllegalArgumentException("MISMATCHED_MATCH_ID");
        }
        transactions.executeWithoutResult(status -> {
            lockMaterialization(matchId);
            var itemCaptures = lockRunItem(runId, matchId);
            validateMaterialization(runId, matchId, materialization, itemCaptures);
            deleteCurrentChildren(matchId);
            upsertParticipantIdentities(materialization);
            upsertMatch(materialization);
            insertTeams(materialization);
            insertParticipants(materialization);
            insertObservations(materialization);
            insertEvents(materialization);
            insertCoverage(materialization);
        });
    }

    @Override
    public void markItemTerminal(
            UUID runId,
            String matchId,
            IngestionItemStatus status,
            String failureCode,
            String failureMessage,
            Instant completedAt) {
        requireHealthy();
        jdbc.update("""
                update league_analysis.ingestion_item
                set status = ?, failure_code = ?, failure_message = ?, completed_at = ?
                where ingestion_run_id = ? and match_id = ?
                """, status.name(), failureCode, failureMessage, timestamp(completedAt), runId, matchId);
    }

    @Override
    public void finishRun(
            UUID runId,
            IngestionRunStatus status,
            String failureCode,
            String failureMessage,
            Instant completedAt) {
        requireHealthy();
        jdbc.update("""
                update league_analysis.ingestion_run
                set status = ?, failure_code = ?, failure_message = ?, completed_at = ?
                where id = ?
                """, status.name(), failureCode, failureMessage, timestamp(completedAt), runId);
    }

    private void attachMatchCapture(UUID runId, ProviderDocument document, UUID captureId) {
        if (document.kind() == SourceKind.MATCH_DETAIL) {
            jdbc.update("""
                    update league_analysis.ingestion_item set detail_capture_id = ?
                    where ingestion_run_id = ? and match_id = ?
                    """, captureId, runId, document.resourceKey());
        } else if (document.kind() == SourceKind.MATCH_TIMELINE) {
            jdbc.update("""
                    update league_analysis.ingestion_item set timeline_capture_id = ?
                    where ingestion_run_id = ? and match_id = ?
                    """, captureId, runId, document.resourceKey());
        }
    }

    private void requireCaptureInRun(UUID runId, CapturedDocument source) {
        var count = jdbc.queryForObject("""
                select count(*) from league_analysis.source_capture
                where id = ? and ingestion_run_id = ?
                """, Integer.class, source.captureId(), runId);
        if (count == null || count != 1) {
            throw new IllegalArgumentException("CAPTURE_NOT_IN_RUN");
        }
    }

    private void lockMaterialization(String matchId) {
        jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))::text",
                String.class,
                matchId);
    }

    private RunItemCaptures lockRunItem(UUID runId, String matchId) {
        var rows = jdbc.query("""
                select detail_capture_id, timeline_capture_id
                from league_analysis.ingestion_item
                where ingestion_run_id = ? and match_id = ?
                for update
                """, (resultSet, rowNumber) -> new RunItemCaptures(
                        resultSet.getObject("detail_capture_id", UUID.class),
                        resultSet.getObject("timeline_capture_id", UUID.class)),
                runId, matchId);
        if (rows.size() != 1) {
            throw new IllegalArgumentException("ITEM_NOT_IN_RUN");
        }
        return rows.getFirst();
    }

    private void validateMaterialization(
            UUID runId,
            String matchId,
            RiotMatchMaterialization materialization,
            RunItemCaptures itemCaptures) {
        var match = materialization.match();
        if (!Objects.equals(itemCaptures.detailCaptureId(), match.detailSourceCaptureId())
                || !Objects.equals(itemCaptures.timelineCaptureId(), match.timelineSourceCaptureId())) {
            throw new IllegalArgumentException("MISMATCHED_CAPTURE_PROVENANCE");
        }
        requireCaptureProvenance(
                runId, match.detailSourceCaptureId(), SourceKind.MATCH_DETAIL, matchId);
        if (match.timelineSourceCaptureId() != null) {
            requireCaptureProvenance(
                    runId, match.timelineSourceCaptureId(), SourceKind.MATCH_TIMELINE, matchId);
        }

        materialization.teams().forEach(team -> requireMatch(matchId, team.matchId()));
        materialization.participants().forEach(participant ->
                requireMatch(matchId, participant.matchId()));
        materialization.observations().forEach(observation -> {
            requireMatch(matchId, observation.matchId());
            requireChildCapture(match.timelineSourceCaptureId(), observation.sourceCaptureId());
        });
        materialization.events().forEach(event -> {
            requireMatch(matchId, event.matchId());
            requireChildCapture(match.timelineSourceCaptureId(), event.sourceCaptureId());
        });
        materialization.coverage().forEach(coverage -> {
            requireMatch(matchId, coverage.matchId());
            var expectedCapture = switch (coverage.sourceKind()) {
                case MATCH_DETAIL -> match.detailSourceCaptureId();
                case MATCH_TIMELINE -> match.timelineSourceCaptureId();
                case ACCOUNT, MATCH_LIST -> throw new IllegalArgumentException(
                        "MISMATCHED_CHILD_PROVENANCE");
            };
            requireChildCapture(expectedCapture, coverage.sourceCaptureId());
        });
    }

    private void requireCaptureProvenance(
            UUID runId,
            UUID captureId,
            SourceKind sourceKind,
            String resourceKey) {
        var count = jdbc.queryForObject("""
                select count(*)
                from league_analysis.source_capture
                where id = ? and ingestion_run_id = ? and source_kind = ? and resource_key = ?
                """, Integer.class, captureId, runId, sourceKind.name(), resourceKey);
        if (count == null || count != 1) {
            throw new IllegalArgumentException("MISMATCHED_CAPTURE_PROVENANCE");
        }
    }

    private void requireMatch(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("MISMATCHED_CHILD_MATCH");
        }
    }

    private void requireChildCapture(UUID expected, UUID actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException("MISMATCHED_CHILD_PROVENANCE");
        }
    }

    private void deleteCurrentChildren(String matchId) {
        jdbc.update("delete from league_analysis.evidence_coverage where match_id = ?", matchId);
        jdbc.update("delete from league_analysis.match_event where match_id = ?", matchId);
        jdbc.update("delete from league_analysis.participant_state_observation where match_id = ?", matchId);
        jdbc.update("delete from league_analysis.riot_participant where match_id = ?", matchId);
        jdbc.update("delete from league_analysis.riot_team where match_id = ?", matchId);
    }

    private void upsertParticipantIdentities(RiotMatchMaterialization materialization) {
        var match = materialization.match();
        for (var participant : materialization.participants()) {
            jdbc.update("""
                    insert into league_analysis.riot_identity
                        (puuid, game_name, tag_line, platform_route, first_observed_at,
                         last_observed_at, last_source_capture_id)
                    values (?, ?, ?, 'NA1',
                        (select captured_at from league_analysis.source_capture where id = ?),
                        (select captured_at from league_analysis.source_capture where id = ?), ?)
                    on conflict (puuid) do update set
                        game_name = case
                            when excluded.last_observed_at >= league_analysis.riot_identity.last_observed_at
                                then coalesce(excluded.game_name, league_analysis.riot_identity.game_name)
                            else league_analysis.riot_identity.game_name
                        end,
                        tag_line = case
                            when excluded.last_observed_at >= league_analysis.riot_identity.last_observed_at
                                then coalesce(excluded.tag_line, league_analysis.riot_identity.tag_line)
                            else league_analysis.riot_identity.tag_line
                        end,
                        platform_route = case
                            when excluded.last_observed_at >= league_analysis.riot_identity.last_observed_at
                                then excluded.platform_route
                            else league_analysis.riot_identity.platform_route
                        end,
                        first_observed_at = least(
                            league_analysis.riot_identity.first_observed_at,
                            excluded.first_observed_at),
                        last_observed_at = greatest(
                            league_analysis.riot_identity.last_observed_at,
                            excluded.last_observed_at),
                        last_source_capture_id = case
                            when excluded.last_observed_at >= league_analysis.riot_identity.last_observed_at
                                then excluded.last_source_capture_id
                            else league_analysis.riot_identity.last_source_capture_id
                        end
                    """,
                    participant.puuid(),
                    participant.gameName(),
                    participant.tagLine(),
                    match.detailSourceCaptureId(),
                    match.detailSourceCaptureId(),
                    match.detailSourceCaptureId());
        }
    }

    private void upsertMatch(RiotMatchMaterialization materialization) {
        var match = materialization.match();
        jdbc.update("""
                insert into league_analysis.riot_match
                    (match_id, game_id, queue_id, map_id, game_mode, game_type, game_version,
                     data_version, game_creation_ms, game_start_ms, game_end_ms,
                     game_duration_seconds, detail_source_capture_id, timeline_source_capture_id,
                     materialization_version, materialized_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (match_id) do update set
                    game_id = excluded.game_id,
                    queue_id = excluded.queue_id,
                    map_id = excluded.map_id,
                    game_mode = excluded.game_mode,
                    game_type = excluded.game_type,
                    game_version = excluded.game_version,
                    data_version = excluded.data_version,
                    game_creation_ms = excluded.game_creation_ms,
                    game_start_ms = excluded.game_start_ms,
                    game_end_ms = excluded.game_end_ms,
                    game_duration_seconds = excluded.game_duration_seconds,
                    detail_source_capture_id = excluded.detail_source_capture_id,
                    timeline_source_capture_id = excluded.timeline_source_capture_id,
                    materialization_version = excluded.materialization_version,
                    materialized_at = excluded.materialized_at
                """,
                match.matchId(), match.gameId(), match.queueId(), match.mapId(),
                match.gameMode(), match.gameType(), match.gameVersion(), match.dataVersion(),
                match.gameCreationMs(), match.gameStartMs(), match.gameEndMs(),
                match.gameDurationSeconds(), match.detailSourceCaptureId(),
                match.timelineSourceCaptureId(), match.materializationVersion());
    }

    private void insertTeams(RiotMatchMaterialization materialization) {
        for (var team : materialization.teams()) {
            jdbc.update("""
                    insert into league_analysis.riot_team
                        (match_id, team_id, win, objectives)
                    values (?, ?, ?, cast(? as jsonb))
                    """, team.matchId(), team.teamId(), team.win(), team.objectives().toString());
        }
    }

    private void insertParticipants(RiotMatchMaterialization materialization) {
        for (var participant : materialization.participants()) {
            jdbc.update("""
                    insert into league_analysis.riot_participant
                        (match_id, participant_id, puuid, team_id, champion_id, champion_name,
                         team_position, kills, deaths, assists, total_minions_killed,
                         neutral_minions_killed, gold_earned, gold_spent, vision_score,
                         summoner_spell_one_id, summoner_spell_two_id, win, end_item_ids)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                    """,
                    participant.matchId(), participant.participantId(), participant.puuid(),
                    participant.teamId(), participant.championId(), participant.championName(),
                    participant.teamPosition(), participant.kills(), participant.deaths(),
                    participant.assists(), participant.totalMinionsKilled(),
                    participant.neutralMinionsKilled(), participant.goldEarned(),
                    participant.goldSpent(), participant.visionScore(),
                    participant.summonerSpellOneId(), participant.summonerSpellTwoId(),
                    participant.win(), json.writeValueAsString(participant.endItemIds()));
        }
    }

    private void insertObservations(RiotMatchMaterialization materialization) {
        for (var observation : materialization.observations()) {
            jdbc.update("""
                    insert into league_analysis.participant_state_observation
                        (id, match_id, participant_id, represented_at_ms, x, y, current_gold,
                         total_gold, level, xp, minions_killed, jungle_minions_killed,
                         source_capture_id, method_version)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    observation.id(), observation.matchId(), observation.participantId(),
                    observation.representedAtMs(), observation.x(), observation.y(),
                    observation.currentGold(), observation.totalGold(), observation.level(),
                    observation.xp(), observation.minionsKilled(), observation.jungleMinionsKilled(),
                    observation.sourceCaptureId(), observation.methodVersion());
        }
    }

    private void insertEvents(RiotMatchMaterialization materialization) {
        for (var event : materialization.events()) {
            jdbc.update("""
                    insert into league_analysis.match_event
                        (id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                         provider_event_type, canonical_event_kind, actor_participant_id,
                         target_participant_id, position_x, position_y, event_payload,
                         source_capture_id, method_version)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                    """,
                    event.id(), event.matchId(), event.representedAtMs(), event.frameAtMs(),
                    event.frameEventIndex(), event.providerEventType(),
                    event.canonicalEventKind().name(), event.actorParticipantId(),
                    event.targetParticipantId(), event.positionX(), event.positionY(),
                    event.eventPayload().toString(), event.sourceCaptureId(), event.methodVersion());
        }
    }

    private void insertCoverage(RiotMatchMaterialization materialization) {
        for (var coverage : materialization.coverage()) {
            jdbc.update("""
                    insert into league_analysis.evidence_coverage
                        (id, match_id, source_kind, signal, status, represented_start_ms,
                         represented_end_ms, details, source_capture_id, method_version)
                    values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                    """,
                    coverage.id(), coverage.matchId(), coverage.sourceKind().name(),
                    coverage.signal(), coverage.status().name(), coverage.representedStartMs(),
                    coverage.representedEndMs(), coverage.details().toString(),
                    coverage.sourceCaptureId(), coverage.methodVersion());
        }
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record RunItemCaptures(UUID detailCaptureId, UUID timelineCaptureId) {}
}
