package dev.leagueanalysis.ingestion.riot.adapter.out.persistence;

import dev.leagueanalysis.privacy.PrivacyHash;
import dev.leagueanalysis.privacy.PrivacyRuntimeGuard;
import dev.leagueanalysis.ingestion.riot.application.IngestionItemStatus;
import dev.leagueanalysis.ingestion.riot.application.IngestionRunStatus;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionCommand;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionStore;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.RiotPayloadException;
import dev.leagueanalysis.ingestion.riot.domain.ParticipantDetails;
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
import dev.leagueanalysis.ingestion.riot.application.TimelineLookup;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Repository
@org.springframework.context.annotation.DependsOn("privacyRuntimeGuard")
public class JdbcRiotIngestionStore implements RiotIngestionStore, dev.leagueanalysis.ingestion.riot.application.PublicMatchLookupStore {
    // Keep cache ownership and account refreshes on the same verified identity
    // snapshot. Failed match imports still contribute successful account evidence.
    private static final String LATEST_VERIFIED_IDENTITY = """
                with identity_evidence as (
                    select r.resolved_puuid puuid, c.captured_at observed_at, r.started_at, 1 priority
                    from league_analysis.ingestion_run r
                    join league_analysis.source_capture c on c.ingestion_run_id = r.id and c.source_kind = 'ACCOUNT'
                    where r.resolved_puuid is not null
                        and lower(r.requested_game_name) = lower(?) and lower(r.requested_tag_line) = lower(?)
                    union all
                    select r.resolved_puuid, c.captured_at, r.started_at, 1
                    from league_analysis.ingestion_run r
                    join league_analysis.source_capture c on c.ingestion_run_id = r.id and c.source_kind = 'ACCOUNT'
                    join league_analysis.source_payload p on p.id = c.source_payload_id and p.source_kind = 'ACCOUNT'
                    where r.resolved_puuid = p.payload_json->>'puuid'
                        and lower(p.payload_json->>'gameName') = lower(?)
                        and lower(p.payload_json->>'tagLine') = lower(?)
                    union all
                    select i.puuid, c.captured_at, r.started_at, 0
                    from league_analysis.riot_identity i
                    join league_analysis.source_capture c on c.id = i.last_source_capture_id and c.source_kind = 'ACCOUNT'
                    join league_analysis.ingestion_run r on r.id = c.ingestion_run_id and r.resolved_puuid = i.puuid
                    where lower(i.game_name) = lower(?) and lower(i.tag_line) = lower(?)
                ), latest_identity as (
                    select puuid from identity_evidence
                    order by observed_at desc, started_at desc, priority desc, puuid limit 1
                )
            """;

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
                     regional_route, queue_id, match_limit, status, started_at, page_start, page_end_time, previous_run_id)
                values (?, '', '', 'NA1', 'AMERICAS', ?, ?, 'RUNNING', ?, ?, ?, ?)
                """, runId, command.queueId(), command.matchLimit(), timestamp(startedAt),
                command.start(), command.endTime(), command.previousRunId());
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
                where match_id = ? and ((queue_id in (400, 420, 430, 440, 480, 490) and map_id = 11)
                    or (queue_id = 450 and map_id in (12, 14))) and timeline_source_capture_id is not null)
                """, Boolean.class, matchId));
    }

    @Override
    public boolean isCompleteMatch(String matchId, String puuid) {
        requireHealthy();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                    select 1
                    from league_analysis.riot_match m
                    join league_analysis.riot_participant p on p.match_id = m.match_id
                    where m.match_id = ? and ((m.queue_id in (400, 420, 430, 440, 480, 490) and m.map_id = 11)
                        or (m.queue_id = 450 and m.map_id in (12, 14)))
                        and m.timeline_source_capture_id is not null
                        and p.puuid = ?
                )
                """, Boolean.class, matchId, puuid));
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
                select max(retry_at) from (
                    select retry_not_before retry_at from league_analysis.ingestion_run where public_request
                    union all select retry_at from league_analysis.rank_refresh_state where error='RATE_LIMITED'
                    union all select retry_at from league_analysis.player_profile_current where error='RATE_LIMITED'
                ) cooldowns
                """, OffsetDateTime.class);
        return Optional.ofNullable(value).map(OffsetDateTime::toInstant);
    }

    @Override
    public Optional<PublicMatchLookup> findFresh(RiotIngestionCommand command, Instant since) {
        requireHealthy();
        return jdbc.query(LATEST_VERIFIED_IDENTITY + """
                select id from league_analysis.ingestion_run
                where public_request and lookup_kind = 'HISTORY' and page_start = 0 and queue_id = ?
                    and status = 'COMPLETE' and completed_at > ?
                    and resolved_puuid is not null and not league_analysis.privacy_blocked('puuid', resolved_puuid)
                    and lower(requested_game_name) = lower(?) and lower(requested_tag_line) = lower(?)
                    and resolved_puuid = coalesce((select puuid from latest_identity), resolved_puuid)
                order by completed_at desc limit 1
                """, (rs, row) -> rs.getObject("id", UUID.class),
                command.gameName(), command.tagLine(), command.gameName(), command.tagLine(),
                command.gameName(), command.tagLine(), command.queueId(), timestamp(since),
                command.gameName(), command.tagLine()).stream().findFirst().flatMap(this::readPublicRun);
    }

    @Override
    public Optional<PublicMatchLookup> readPublicRun(UUID runId) {
        requireHealthy();
        return jdbc.query("""
                select id, requested_game_name, requested_tag_line, status, failure_code, retry_not_before,
                    queue_id, completed_at, case when page_start > 0 then previous_run_id end as previous_run_id, has_more
                from league_analysis.ingestion_run r where id = ? and public_request and lookup_kind = 'HISTORY'
                    and not league_analysis.privacy_blocked('puuid', resolved_puuid)
                """, (rs, row) -> {
                    String status = rs.getString("status");
                    var matches = publicMatches(runId);
                    if ("COMPLETE".equals(status) && matches.isEmpty()) status = "EMPTY";
                    var retry = rs.getObject("retry_not_before", OffsetDateTime.class);
                    var completed = rs.getObject("completed_at", OffsetDateTime.class);
                    var nextRefresh = readPageCommand(runId).flatMap(this::latestRefresh).map(time -> time.plusSeconds(900)).orElse(null);
                    return new PublicMatchLookup(runId, rs.getString("requested_game_name"),
                            rs.getString("requested_tag_line"), status, publicMessage(rs.getString("failure_code")),
                            retry == null ? null : retry.toInstant(), matches, rs.getInt("queue_id"),
                            "RUNNING".equals(status) || completed == null ? null : completed.toInstant(), nextRefresh,
                            rs.getObject("previous_run_id", UUID.class), rs.getBoolean("has_more"));
                }, runId).stream().findFirst();
    }

    @Override
    public Optional<RiotIngestionCommand> readPageCommand(UUID runId) {
        requireHealthy();
        return jdbc.query("""
                select requested_game_name, requested_tag_line, match_limit, queue_id,
                    page_start, page_end_time, previous_run_id
                from league_analysis.ingestion_run r
                where id = ? and public_request and lookup_kind = 'HISTORY'
                    and resolved_puuid is not null and requested_game_name <> '' and requested_tag_line <> ''
                    and not league_analysis.privacy_blocked('puuid', resolved_puuid)
                    and exists(select 1 from league_analysis.riot_identity p where p.puuid = r.resolved_puuid)
                    and exists(select 1 from league_analysis.source_capture c
                        where c.ingestion_run_id = r.id and c.source_kind = 'ACCOUNT')
                """, (rs, row) -> new RiotIngestionCommand(rs.getString("requested_game_name"),
                rs.getString("requested_tag_line"), rs.getInt("match_limit"), rs.getInt("queue_id"),
                rs.getInt("page_start"), rs.getObject("page_end_time", Long.class),
                rs.getObject("previous_run_id", UUID.class)), runId).stream().findFirst();
    }

    @Override
    public boolean matchesPageIdentity(UUID previousRunId, String puuid) {
        requireHealthy();
        return readPageCommand(previousRunId).isPresent() && Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from league_analysis.ingestion_run where id = ? and resolved_puuid = ?)
                """, Boolean.class, previousRunId, puuid));
    }

    @Override
    public Optional<PublicMatchLookup> findOlder(UUID previousRunId) {
        requireHealthy();
        if (readPageCommand(previousRunId).isEmpty()) return Optional.empty();
        return jdbc.query("""
                select r.id from league_analysis.ingestion_run r
                join league_analysis.ingestion_run p on p.id = r.previous_run_id
                where r.previous_run_id = ? and r.public_request and r.lookup_kind = 'HISTORY'
                    and r.status = 'COMPLETE' and r.queue_id = p.queue_id
                    and r.resolved_puuid = p.resolved_puuid
                    and r.page_start = p.page_start + p.match_limit
                    and r.page_end_time is not distinct from p.page_end_time
                order by r.completed_at desc, r.id desc limit 1
                """, (rs, row) -> rs.getObject("id", UUID.class), previousRunId)
                .stream().findFirst().flatMap(this::readPublicRun);
    }

    @Override
    public Optional<Instant> latestRefresh(RiotIngestionCommand command) {
        requireHealthy();
        var value = jdbc.queryForObject(LATEST_VERIFIED_IDENTITY + """
                select max(r.started_at) from league_analysis.ingestion_run r, latest_identity t
                where r.public_request and r.lookup_kind = 'HISTORY' and r.page_start = 0
                    and r.status in ('RUNNING', 'COMPLETE') and r.resolved_puuid = t.puuid
                    and not league_analysis.privacy_blocked('puuid', r.resolved_puuid)
                    and exists(select 1 from league_analysis.riot_identity p where p.puuid = r.resolved_puuid)
                    and exists(select 1 from league_analysis.source_capture c
                        where c.ingestion_run_id = r.id and c.source_kind = 'ACCOUNT')
                """, OffsetDateTime.class, command.gameName(), command.tagLine(), command.gameName(), command.tagLine(),
                command.gameName(), command.tagLine());
        return Optional.ofNullable(value).map(OffsetDateTime::toInstant);
    }

    @Override
    public void recordPageSize(UUID runId, int providerCount) {
        requireHealthy();
        if (providerCount < 0) throw new IllegalArgumentException("INVALID_PAGE_SIZE");
        jdbc.update("update league_analysis.ingestion_run set has_more = (? = match_limit) where id = ? and lookup_kind = 'HISTORY'",
                providerCount, runId);
    }

    @Override
    public boolean hasSummary(String matchId, String puuid, int queueId) {
        requireHealthy();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from league_analysis.riot_match m
                    join league_analysis.riot_participant p on p.match_id = m.match_id
                    where m.match_id = ? and (? = 0 or m.queue_id = ?) and p.puuid = ?
                        and ((m.queue_id in (400, 420, 430, 440, 480, 490) and m.map_id = 11)
                        or (m.queue_id = 450 and m.map_id in (12, 14)))
                        and not league_analysis.privacy_blocked('match', m.match_id)
                        and not league_analysis.privacy_blocked('puuid', p.puuid))
                """, Boolean.class, matchId, queueId, queueId, puuid));
    }

    @Override
    public Optional<ProviderDocument> storedDetail(String matchId) {
        requireHealthy();
        return jdbc.query("""
                select c.resource_key, c.captured_at, c.http_status, c.regional_route, c.platform_route,
                    c.provider_game_version, p.body_sha256, p.body_size_bytes, p.payload_json::text,
                    c.response_metadata::text, c.parser_version, c.attempt
                from league_analysis.riot_match m
                join league_analysis.source_capture c on c.id = m.detail_source_capture_id
                join league_analysis.source_payload p on p.id = c.source_payload_id
                where m.match_id = ? and ((m.queue_id in (400, 420, 430, 440, 480, 490) and m.map_id = 11)
                        or (m.queue_id = 450 and m.map_id in (12, 14)))
                    and c.source_kind = 'MATCH_DETAIL' and p.source_kind = 'MATCH_DETAIL'
                    and c.resource_key = m.match_id
                    and not league_analysis.privacy_blocked('match', m.match_id)
                """, (rs, row) -> new ProviderDocument(SourceKind.MATCH_DETAIL, rs.getString("resource_key"),
                rs.getObject("captured_at", OffsetDateTime.class).toInstant(), rs.getInt("http_status"),
                rs.getString("regional_route"), rs.getString("platform_route"), rs.getString("provider_game_version"),
                rs.getString("body_sha256"), rs.getInt("body_size_bytes"), json.readTree(rs.getString("payload_json")),
                json.readTree(rs.getString("response_metadata")), rs.getString("parser_version"), rs.getInt("attempt")),
                matchId).stream().findFirst().filter(document -> !isExcludedDocument(document));
    }

    @Override
    public UUID startTimelineRun(String matchId, Instant now) {
        requireHealthy();
        return Objects.requireNonNull(transactions.execute(status -> {
            lockMaterialization(matchId);
            if (storedDetail(matchId).isEmpty()) throw new IllegalArgumentException("MATCH_NOT_AVAILABLE");
            var id = UUID.randomUUID();
            jdbc.update("""
                    insert into league_analysis.ingestion_run
                        (id, requested_game_name, requested_tag_line, platform_route, regional_route,
                         queue_id, match_limit, status, started_at, public_request, lookup_kind)
                    select ?, '', '', 'NA1', 'AMERICAS', queue_id, 1, 'RUNNING', ?, true, 'TIMELINE'
                    from league_analysis.riot_match where match_id = ?
                    """, id, timestamp(now), matchId);
            addItems(id, List.of(matchId));
            return id;
        }));
    }

    @Override
    public Optional<TimelineLookup> readTimeline(String matchId) {
        requireHealthy();
        if (storedDetail(matchId).isEmpty()) return Optional.empty();
        if (isCompleteMatch(matchId)) {
            return Optional.of(new TimelineLookup(
                    matchId, null, "AVAILABLE", null, null));
        }
        var runs = jdbc.query("""
                select r.id, r.status, r.failure_code, r.retry_not_before
                from league_analysis.ingestion_run r
                join league_analysis.ingestion_item i on i.ingestion_run_id = r.id
                where r.public_request and r.lookup_kind = 'TIMELINE' and i.match_id = ?
                order by (r.status = 'RUNNING') desc, r.started_at desc, r.id desc limit 1
                """, (rs, row) -> {
                    var code = rs.getString("failure_code");
                    var state = rs.getString("status");
                    if (!"RUNNING".equals(state)) state = "PARTIAL".equals(state) || "NOT_FOUND".equals(code)
                            ? "UNAVAILABLE" : "FAILED";
                    var retry = rs.getObject("retry_not_before", OffsetDateTime.class);
                    String message = "RUNNING".equals(state) ? null : "UNAVAILABLE".equals(state)
                            ? "Timeline data is unavailable for this match."
                            : "RATE_LIMITED".equals(code) ? "Riot is cooling down. Try again after the indicated time."
                            : "Timeline could not be loaded. Try again.";
                    return new TimelineLookup(matchId,
                            rs.getObject("id", UUID.class), state, message, retry == null ? null : retry.toInstant());
                }, matchId);
        return runs.stream().findFirst().or(() -> Optional.of(new TimelineLookup(
                matchId, null, "NOT_REQUESTED", null, null)));
    }

    private List<PublicMatchLookup.MatchSummary> publicMatches(UUID runId) {
        var matches = queryPublicMatches(runId);
        // Only this already admitted page, at most twenty matches. Each enrichment
        // commits separately and never acquires account/profile locks.
        for (var match : matches) enrichParticipantDetails(match.matchId());
        // Another reader may have completed the same backfill while this one waited.
        return matches.isEmpty() ? matches : queryPublicMatches(runId);
    }

    private List<PublicMatchLookup.MatchSummary> queryPublicMatches(UUID runId) {
        return jdbc.query("""
                select m.match_id, m.queue_id, p.participant_id, p.champion_name, p.champion_id, m.game_version, p.end_item_ids::text, p.team_position,
                    p.win, coalesce(m.game_start_ms, m.game_creation_ms) started_at_ms, m.game_duration_seconds,
                    p.kills, p.deaths, p.assists, p.total_minions_killed + p.neutral_minions_killed cs,
                    p.gold_earned, m.timeline_source_capture_id is not null timeline_available,
                    (select case when count(*) = 10 and count(e.game_ended_in_early_surrender) = 10
                        then case when bool_and(e.game_ended_in_early_surrender) then true
                            when not bool_or(e.game_ended_in_early_surrender) then false end
                        end
                     from league_analysis.riot_participant e where e.match_id = m.match_id
                        and not league_analysis.privacy_blocked('puuid', e.puuid)) remake
                from league_analysis.ingestion_run r
                join league_analysis.ingestion_item i on i.ingestion_run_id = r.id
                join league_analysis.riot_match m on m.match_id = i.match_id
                    and (r.queue_id = 0 or m.queue_id = r.queue_id)
                    and ((m.queue_id in (400, 420, 430, 440, 480, 490) and m.map_id = 11)
                        or (m.queue_id = 450 and m.map_id in (12, 14)))
                join league_analysis.riot_participant p on p.match_id = m.match_id and p.puuid = r.resolved_puuid
                where r.id = ? and r.public_request and r.lookup_kind = 'HISTORY' and i.status in ('COMPLETE', 'PARTIAL')
                    and not league_analysis.privacy_blocked('puuid', p.puuid)
                    and not league_analysis.privacy_blocked('match', m.match_id)
                order by i.ordinal limit 20
                """, (rs, row) -> new PublicMatchLookup.MatchSummary(rs.getString("match_id"),
                rs.getInt("participant_id"), rs.getString("champion_name"), rs.getInt("champion_id"),
                rs.getString("game_version"), itemIds(rs.getString("end_item_ids")),
                rs.getString("team_position"), rs.getBoolean("win"), rs.getLong("started_at_ms"),
                rs.getLong("game_duration_seconds"), rs.getInt("kills"), rs.getInt("deaths"),
                rs.getInt("assists"), rs.getInt("cs"), rs.getInt("gold_earned"),
                rs.getBoolean("timeline_available"), rs.getInt("queue_id"), rs.getObject("remake", Boolean.class)), runId);
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
            // Summary refreshes can race an optional timeline import. Validate their
            // own evidence first, then preserve the already complete materialization.
            if (materialization.match().timelineSourceCaptureId() == null && isCompleteMatch(matchId)) return;
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
    public boolean enrichParticipantDetails(String matchId) {
        requireHealthy();
        return Boolean.TRUE.equals(transactions.execute(status -> {
            // Match removal takes this gate before table locks. Take it before
            // match/participant locks too, including when no write is necessary.
            jdbc.queryForObject("select pg_advisory_xact_lock_shared(?)::text", String.class,
                    PrivacyRuntimeGuard.WRITE_LOCK);
            requireHealthy();
            lockMaterialization(matchId);
            var sources = jdbc.query("""
                    select m.detail_source_capture_id, c.source_payload_id, m.game_version, m.data_version,
                        m.queue_id, m.map_id
                    from league_analysis.riot_match m
                    join league_analysis.source_capture c on c.id=m.detail_source_capture_id
                    where m.match_id=? and c.source_kind='MATCH_DETAIL' and c.resource_key=m.match_id
                        and not league_analysis.privacy_blocked('match', m.match_id)
                    for update of m
                    """, (rs, row) -> new DetailExtensionSource(rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6)), matchId);
            if (sources.isEmpty()) return false;
            var current = sources.getFirst();
            var participants = jdbc.query("""
                    select participant_id, puuid, team_id, champion_id, detail_extension_version
                    from league_analysis.riot_participant where match_id=? order by participant_id for update
                    """, (rs, row) -> new ExtensionParticipant(rs.getInt(1), rs.getString(2), rs.getInt(3),
                    rs.getInt(4), rs.getString(5)), matchId);
            if (participants.isEmpty() || participants.stream().noneMatch(p -> p.version() == null)) return false;
            // Unknown/newer versions belong to another contract; never downgrade them.
            if (participants.stream().anyMatch(p -> p.version() != null && !ParticipantDetails.VERSION.equals(p.version())))
                return false;
            var source = storedDetail(matchId);
            if (source.isEmpty()) return false;
            var document = source.orElseThrow();
            RiotMatchMaterialization decoded;
            try {
                decoded = new MatchV5Decoder().decode(new CapturedDocument(current.payloadId(),
                        current.captureId(), document), Optional.empty());
            } catch (IllegalArgumentException | RiotPayloadException invalidRetainedDetail) {
                // Keep old rows available with explicitly missing extensions.
                return false;
            }
            var match = decoded.match();
            if (!matchId.equals(match.matchId()) || !current.gameVersion().equals(match.gameVersion())
                    || !current.dataVersion().equals(match.dataVersion()) || current.queueId() != match.queueId()
                    || current.mapId() != match.mapId()
                    || (document.providerGameVersion() != null && !current.gameVersion().equals(document.providerGameVersion())))
                return false;
            var memberships = decoded.participants().stream().map(p -> new ExtensionParticipant(
                    p.participantId(), p.puuid(), p.teamId(), p.championId(), null))
                    .sorted(java.util.Comparator.comparingInt(ExtensionParticipant::id)).toList();
            var expected = participants.stream().map(p -> new ExtensionParticipant(p.id(), p.puuid(),
                    p.teamId(), p.championId(), null)).toList();
            if (!memberships.equals(expected)) return false;
            requireHealthy();
            for (var participant : decoded.participants()) {
                if (participants.stream().anyMatch(p -> p.id() == participant.participantId() && p.version() == null))
                    writeParticipantDetails(participant);
            }
            return true;
        }));
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
            writeParticipantDetails(participant);
        }
    }

    private void writeParticipantDetails(dev.leagueanalysis.ingestion.riot.domain.ParticipantFact participant) {
        var details = participant.details();
        jdbc.update("""
                update league_analysis.riot_participant set rune_snapshot=cast(? as jsonb),
                    participant_totals=cast(? as jsonb), game_ended_in_early_surrender=?,
                    game_ended_in_surrender=?, team_early_surrendered=?, detail_extension_version=?
                where match_id=? and participant_id=? and puuid=?
                """, details.runes() == null ? null : json.writeValueAsString(details.runes()),
                json.writeValueAsString(details.totals()), details.gameEndedInEarlySurrender(),
                details.gameEndedInSurrender(), details.teamEarlySurrendered(),
                dev.leagueanalysis.ingestion.riot.domain.ParticipantDetails.VERSION,
                participant.matchId(), participant.participantId(), participant.puuid());
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
    private record DetailExtensionSource(UUID captureId, UUID payloadId, String gameVersion, String dataVersion,
            int queueId, int mapId) {}
    private record ExtensionParticipant(int id, String puuid, int teamId, int championId, String version) {}
}
