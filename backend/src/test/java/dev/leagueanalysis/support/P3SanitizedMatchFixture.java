package dev.leagueanalysis.support;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class P3SanitizedMatchFixture {
    public static final String MATCH_ID = "NA1_9000000001";
    public static final UUID DETAIL_CAPTURE_ID = uuid(4);
    public static final UUID TIMELINE_CAPTURE_ID = uuid(5);
    public static final UUID ALTERNATE_TIMELINE_CAPTURE_ID = uuid(6);

    private static final UUID RUN_ID = uuid(1);
    private static final UUID DETAIL_PAYLOAD_ID = uuid(2);
    private static final UUID TIMELINE_PAYLOAD_ID = uuid(3);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate fixtureWrites;

    public P3SanitizedMatchFixture(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.fixtureWrites = new TransactionTemplate(transactionManager);
    }

    public void replaceFixtureMatch() {
        fixtureWrites.executeWithoutResult(status -> {
            removeFixtureMatch();
            seedSourcesAndMatch();
            seedRoster();
            seedObservations();
            seedAnchorsAndItemTransitions();
            seedCoverage();
        });
    }

    /** Enrich the invented browser story without changing the persistence test baseline. */
    public void prepareBrowserEvidence() {
        fixtureWrites.executeWithoutResult(status -> {
            jdbc.update("""
                    update league_analysis.participant_state_observation
                    set represented_at_ms = 500210
                    where match_id = ? and represented_at_ms = 480210
                    """, MATCH_ID);
            jdbc.update("""
                    update league_analysis.participant_state_observation
                    set total_gold = total_gold + case
                        when represented_at_ms = 780275 then 719
                        when participant_id in (6, 7) then 505 else 504 end
                    where match_id = ? and (
                        (participant_id = 6 and represented_at_ms = 780275)
                        or (participant_id between 6 and 10 and represented_at_ms = 900291))
                    """, MATCH_ID);
            insertItem(311, 814_821, 814_000, 0, "ITEM_PURCHASED", 6,
                    "itemId", 6631, null, null);
            // Match the complete reducer result, including the later E4 purchase/sale.
            insertItem(312, 1_520_512, 1_520_000, 0, "ITEM_PURCHASED", 6,
                    "itemId", 2006, null, null);
            jdbc.update("""
                    update league_analysis.riot_participant
                    set end_item_ids = '[6631,3006,0,0,0,0,0]'::jsonb
                    where match_id = ? and participant_id = 6
                    """, MATCH_ID);
        });
    }

    private void removeFixtureMatch() {
        jdbc.update("delete from league_analysis.riot_match where match_id = ?", MATCH_ID);
        for (int participantId = 1; participantId <= 10; participantId++) {
            jdbc.update(
                    "delete from league_analysis.riot_identity where puuid = ?",
                    "fixture-identity-" + participantId);
        }
        jdbc.update("delete from league_analysis.ingestion_item where ingestion_run_id = ?", RUN_ID);
        jdbc.update(
                "delete from league_analysis.source_capture where id in (?, ?, ?)",
                DETAIL_CAPTURE_ID,
                TIMELINE_CAPTURE_ID,
                ALTERNATE_TIMELINE_CAPTURE_ID);
        jdbc.update("delete from league_analysis.ingestion_run where id = ?", RUN_ID);
        jdbc.update(
                "delete from league_analysis.source_payload where id in (?, ?)",
                DETAIL_PAYLOAD_ID,
                TIMELINE_PAYLOAD_ID);
    }

    private void seedSourcesAndMatch() {
        jdbc.update("""
                insert into league_analysis.ingestion_run
                    (id, requested_game_name, requested_tag_line, platform_route, regional_route,
                     queue_id, match_limit, status, started_at, completed_at)
                values (?, 'Fixture Subject', 'TEST', 'NA1', 'AMERICAS',
                        420, 1, 'COMPLETE', ?, ?)
                """, RUN_ID, timestamp(), timestamp());
        insertPayload(DETAIL_PAYLOAD_ID, "MATCH_DETAIL", "a");
        insertPayload(TIMELINE_PAYLOAD_ID, "MATCH_TIMELINE", "b");
        insertCapture(DETAIL_CAPTURE_ID, DETAIL_PAYLOAD_ID, "MATCH_DETAIL", 1);
        insertCapture(TIMELINE_CAPTURE_ID, TIMELINE_PAYLOAD_ID, "MATCH_TIMELINE", 1);
        insertCapture(ALTERNATE_TIMELINE_CAPTURE_ID, TIMELINE_PAYLOAD_ID, "MATCH_TIMELINE", 2);
        jdbc.update("""
                insert into league_analysis.riot_match
                    (match_id, game_id, queue_id, map_id, game_mode, game_type, game_version,
                     data_version, game_creation_ms, game_start_ms, game_end_ms,
                     game_duration_seconds, detail_source_capture_id, timeline_source_capture_id,
                     materialization_version, materialized_at)
                values (?, 9000000001, 420, 11, 'CLASSIC', 'MATCHED_GAME', '16.17.810.4348',
                        '2', 1788451200000, 1788451210000, 1788453286000,
                        2076, ?, ?, 'sanitized-materializer-v1', ?)
                """, MATCH_ID, DETAIL_CAPTURE_ID, TIMELINE_CAPTURE_ID, timestamp());
        jdbc.update("""
                insert into league_analysis.riot_team (match_id, team_id, win, objectives)
                values (?, 100, false, jsonb_build_object()),
                       (?, 200, true, jsonb_build_object())
                """, MATCH_ID, MATCH_ID);
    }

    private void seedRoster() {
        var champions = new String[] {
            "Aster", "Bramble", "Cinder", "Dune", "Ember",
            "Flint", "Gale", "Harbor", "Ivory", "Juniper"
        };
        var positions = new String[] {
            "TOP", "JUNGLE", "MIDDLE", "BOTTOM", "UTILITY",
            "TOP", "JUNGLE", "MIDDLE", "BOTTOM", "UTILITY"
        };
        for (int participantId = 1; participantId <= 10; participantId++) {
            var syntheticIdentity = "fixture-identity-" + participantId;
            var teamId = participantId <= 5 ? 100 : 200;
            jdbc.update("""
                    insert into league_analysis.riot_identity
                        (puuid, first_observed_at, last_observed_at, last_source_capture_id)
                    values (?, ?, ?, ?)
                    """, syntheticIdentity, timestamp(), timestamp(), DETAIL_CAPTURE_ID);
            jdbc.update("""
                    insert into league_analysis.riot_participant
                        (match_id, participant_id, puuid, team_id, champion_id, champion_name,
                         team_position, kills, deaths, assists, total_minions_killed,
                         neutral_minions_killed, gold_earned, gold_spent, vision_score,
                         summoner_spell_one_id, summoner_spell_two_id, win, end_item_ids)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 4, 14, ?,
                            to_jsonb(array[?, ?, 0, 0, 0, 0, ?]::integer[]))
                    """,
                    MATCH_ID,
                    participantId,
                    syntheticIdentity,
                    teamId,
                    700 + participantId,
                    champions[participantId - 1],
                    positions[participantId - 1],
                    participantId == 6 ? 5 : 2,
                    participantId == 6 ? 2 : 3,
                    participantId == 7 ? 7 : 4,
                    120 + participantId,
                    participantId % 3,
                    10_000 + participantId * 100,
                    9_000 + participantId * 100,
                    12 + participantId,
                    teamId == 200,
                    1_000 + participantId,
                    2_000 + participantId,
                    teamId == 100 ? 3340 : 3364);
        }
    }

    private void seedObservations() {
        long[] representedTimes = {
            480_210L, 600_228L, 780_275L, 900_291L,
            1_080_335L, 1_200_389L, 1_500_511L, 1_620_521L
        };
        int[][] totalGold = {
            {4700, 4720, 4740, 4700, 4711, 4600, 4620, 4580, 4600, 4600},
            {4830, 4840, 4860, 4810, 4809, 5000, 5010, 4970, 5010, 5010},
            {5700, 5680, 5720, 5660, 5690, 5750, 5730, 5710, 5690, 5670},
            {6200, 6180, 6220, 6160, 6190, 6550, 6300, 6260, 6240, 6220},
            {7350, 7310, 7280, 7260, 7240, 7480, 7390, 7340, 7300, 7280},
            {7900, 7840, 7790, 7750, 7720, 7750, 7680, 7640, 7600, 7560},
            {9800, 9700, 9620, 9560, 9500, 10_050, 9900, 9800, 9700, 9600},
            {10_500, 10_350, 10_250, 10_150, 10_050, 10_300, 10_250, 10_200, 10_150, 10_100}
        };
        long observationId = 100;
        for (int sample = 0; sample < representedTimes.length; sample++) {
            for (int participantId = 1; participantId <= 10; participantId++) {
                var total = totalGold[sample][participantId - 1];
                jdbc.update("""
                        insert into league_analysis.participant_state_observation
                            (id, match_id, participant_id, represented_at_ms, x, y,
                             current_gold, total_gold, level, xp, minions_killed,
                             jungle_minions_killed, source_capture_id, method_version)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                'sanitized-timeline-observation-v1')
                        """,
                        uuid(observationId++),
                        MATCH_ID,
                        participantId,
                        representedTimes[sample],
                        1_000 + participantId * 120 + sample * 10,
                        2_000 + participantId * 90 + sample * 10,
                        total % 1_000,
                        total,
                        5 + sample / 2,
                        1_000 + sample * 500 + participantId * 10,
                        30 + sample * 12 + participantId,
                        participantId == 2 || participantId == 7 ? 20 + sample * 4 : 0,
                        TIMELINE_CAPTURE_ID);
            }
        }
    }

    private void seedAnchorsAndItemTransitions() {
        insertKill(301, 540_215, 540_000, 1, 6, 1, 7, 5_200, 6_100);
        insertObjective(302, 570_220, 570_000, 2, "ELITE_MONSTER_KILL", 2,
                "killerTeamId", 100, "monsterType", "DRAGON", 4_900, 5_900);

        insertObjective(303, 840_281, 840_000, 0, "ELITE_MONSTER_KILL", 7,
                "killerTeamId", 200, "monsterType", "DRAGON", 8_600, 7_900);
        insertStructure(304, 870_286, 870_000, 1, "BUILDING_KILL", 6, 200,
                "TOWER_BUILDING", "TOP_LANE", "OUTER_TURRET", 9_100, 8_200);

        insertKill(305, 1_140_360, 1_140_000, 0, 1, 6, 2, 6_000, 6_600);
        insertObjective(306, 1_170_375, 1_170_000, 1, "ELITE_MONSTER_KILL", 2,
                "killerTeamId", 100, "monsterType", "RIFTHERALD", 6_400, 6_300);

        insertItem(307, 1_530_513, 1_530_000, 0, "ITEM_PURCHASED", 6,
                "itemId", 3_006, null, null);
        insertKill(308, 1_560_516, 1_560_000, 1, 6, 2, 7, 7_600, 7_100);
        insertStructure(309, 1_560_516, 1_560_000, 2, "TURRET_PLATE_DESTROYED", 1, 100,
                "TOWER_BUILDING", "TOP_LANE", "OUTER_TURRET", 7_500, 7_000);
        insertItem(310, 1_590_518, 1_590_000, 0, "ITEM_SOLD", 6,
                "itemId", 2_006, null, null);
    }

    private void seedCoverage() {
        insertCoverage(401, "participant_positions", "OBSERVED");
        insertCoverage(402, "economy_snapshots", "OBSERVED");
        insertCoverage(403, "item_events", "OBSERVED");
        insertCoverage(404, "objective_events", "OBSERVED");
        insertCoverage(405, "champion_kills", "OBSERVED");
    }

    private void insertPayload(UUID id, String kind, String hashCharacter) {
        jdbc.update("""
                insert into league_analysis.source_payload
                    (id, source_kind, body_sha256, body_size_bytes, payload_json)
                values (?, ?, ?, 0, jsonb_build_object())
                """, id, kind, hashCharacter.repeat(64));
    }

    private void insertCapture(UUID id, UUID payloadId, String kind, int attempt) {
        jdbc.update("""
                insert into league_analysis.source_capture
                    (id, ingestion_run_id, source_payload_id, source_kind, resource_key,
                     regional_route, platform_route, captured_at, http_status,
                     response_metadata, parser_version, attempt)
                values (?, ?, ?, ?, ?, 'AMERICAS', 'NA1', ?, 200,
                        jsonb_build_object(), 'sanitized-parser-v1', ?)
                """, id, RUN_ID, payloadId, kind, MATCH_ID, timestamp(), attempt);
    }

    private void insertKill(
            long id,
            long representedAtMs,
            long frameAtMs,
            int eventIndex,
            int actorParticipantId,
            int targetParticipantId,
            int assisterParticipantId,
            int x,
            int y) {
        jdbc.update("""
                insert into league_analysis.match_event
                    (id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                     provider_event_type, canonical_event_kind, actor_participant_id,
                     target_participant_id, position_x, position_y, event_payload,
                     source_capture_id, method_version)
                values (?, ?, ?, ?, ?, 'CHAMPION_KILL', 'CHAMPION_KILL', ?, ?, ?, ?,
                        jsonb_build_object(
                            'assistingParticipantIds', to_jsonb(array[?]::integer[])),
                        ?, 'sanitized-event-v1')
                """, uuid(id), MATCH_ID, representedAtMs, frameAtMs, eventIndex,
                actorParticipantId, targetParticipantId, x, y, assisterParticipantId,
                TIMELINE_CAPTURE_ID);
    }

    private void insertObjective(
            long id,
            long representedAtMs,
            long frameAtMs,
            int eventIndex,
            String providerEventType,
            Integer actorParticipantId,
            String teamField,
            int teamId,
            String descriptorField,
            String descriptor,
            int x,
            int y) {
        jdbc.update("""
                insert into league_analysis.match_event
                    (id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                     provider_event_type, canonical_event_kind, actor_participant_id,
                     position_x, position_y, event_payload, source_capture_id, method_version)
                values (?, ?, ?, ?, ?, ?, 'OBJECTIVE', ?, ?, ?,
                        jsonb_build_object(cast(? as text), ?, cast(? as text), cast(? as text)),
                        ?, 'sanitized-event-v1')
                """, uuid(id), MATCH_ID, representedAtMs, frameAtMs, eventIndex,
                providerEventType, actorParticipantId, x, y, teamField, teamId,
                descriptorField, descriptor, TIMELINE_CAPTURE_ID);
    }

    private void insertStructure(
            long id,
            long representedAtMs,
            long frameAtMs,
            int eventIndex,
            String providerEventType,
            Integer actorParticipantId,
            int teamId,
            String buildingType,
            String laneType,
            String towerType,
            int x,
            int y) {
        jdbc.update("""
                insert into league_analysis.match_event
                    (id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                     provider_event_type, canonical_event_kind, actor_participant_id,
                     position_x, position_y, event_payload, source_capture_id, method_version)
                values (?, ?, ?, ?, ?, ?, 'OBJECTIVE', ?, ?, ?,
                        jsonb_build_object(
                            'teamId', ?, 'buildingType', ?, 'laneType', ?, 'towerType', ?),
                        ?, 'sanitized-event-v1')
                """, uuid(id), MATCH_ID, representedAtMs, frameAtMs, eventIndex,
                providerEventType, actorParticipantId, x, y, teamId, buildingType,
                laneType, towerType, TIMELINE_CAPTURE_ID);
    }

    private void insertItem(
            long id,
            long representedAtMs,
            long frameAtMs,
            int eventIndex,
            String providerEventType,
            int actorParticipantId,
            String itemField,
            Integer itemId,
            Integer beforeId,
            Integer afterId) {
        jdbc.update("""
                insert into league_analysis.match_event
                    (id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                     provider_event_type, canonical_event_kind, actor_participant_id,
                     event_payload, source_capture_id, method_version)
                values (?, ?, ?, ?, ?, ?, 'ITEM', ?,
                        jsonb_strip_nulls(jsonb_build_object(
                            cast(? as text), cast(? as integer),
                            'beforeId', cast(? as integer),
                            'afterId', cast(? as integer))),
                        ?, 'sanitized-event-v1')
                """, uuid(id), MATCH_ID, representedAtMs, frameAtMs, eventIndex,
                providerEventType, actorParticipantId, itemField, itemId, beforeId,
                afterId, TIMELINE_CAPTURE_ID);
    }

    private void insertCoverage(long id, String signal, String status) {
        jdbc.update("""
                insert into league_analysis.evidence_coverage
                    (id, match_id, source_kind, signal, status, represented_start_ms,
                     represented_end_ms, details, source_capture_id, method_version)
                values (?, ?, 'MATCH_TIMELINE', ?, ?, 0, 2076000,
                        jsonb_build_object(), ?, 'sanitized-coverage-v1')
                """, uuid(id), MATCH_ID, signal, status, TIMELINE_CAPTURE_ID);
    }

    private static OffsetDateTime timestamp() {
        return OffsetDateTime.of(2026, 9, 3, 12, 0, 0, 0, ZoneOffset.UTC);
    }

    public static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
