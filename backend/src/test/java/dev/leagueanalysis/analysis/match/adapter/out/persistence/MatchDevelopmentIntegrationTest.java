package dev.leagueanalysis.analysis.match.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.leagueanalysis.demo.DemoSeedCommand;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.application.IngestionItemStatus;
import dev.leagueanalysis.ingestion.riot.application.IngestionRunStatus;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionCommand;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionStore;
import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.SourceKind;
import dev.leagueanalysis.support.P3SanitizedMatchFixture;
import dev.leagueanalysis.support.PostgresTestConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class MatchDevelopmentIntegrationTest {
    @Autowired DemoSeedCommand seedCommand;
    @Autowired RiotIngestionStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired P3SanitizedMatchFixture unrelatedFixture;

    @BeforeEach
    @AfterEach
    void removeDemoData() {
        jdbc.update("delete from league_analysis.riot_match where match_id = ?", DemoSeedCommand.MATCH_ID);
        jdbc.update("delete from league_analysis.riot_identity where puuid like 'demo-match-v1-%'");
        var runIds = jdbc.queryForList("""
                select distinct ingestion_run_id from league_analysis.source_capture
                where resource_key = ?
                """, java.util.UUID.class, DemoSeedCommand.MATCH_ID);
        for (var runId : runIds) {
            jdbc.update("delete from league_analysis.ingestion_item where ingestion_run_id = ?", runId);
        }
        jdbc.update("delete from league_analysis.source_capture where resource_key = ?", DemoSeedCommand.MATCH_ID);
        for (var runId : runIds) {
            jdbc.update("delete from league_analysis.ingestion_run where id = ?", runId);
        }
        jdbc.update("""
                delete from league_analysis.source_payload p
                where not exists (
                    select 1 from league_analysis.source_capture c where c.source_payload_id = p.id)
                """);
    }

    @Test
    void directLegacyMatchAccessEnrichesRunesWithoutChangingTimelineEvidence() throws Exception {
        seedCommand.seed();
        var timelineCapture = jdbc.queryForObject("select timeline_source_capture_id from league_analysis.riot_match where match_id=?", java.util.UUID.class, DemoSeedCommand.MATCH_ID);
        var eventCount = jdbc.queryForObject("select count(*) from league_analysis.match_event where match_id=?", Integer.class, DemoSeedCommand.MATCH_ID);
        jdbc.update("update league_analysis.riot_participant set rune_snapshot=null, participant_totals=null, detail_extension_version=null where match_id=?", DemoSeedCommand.MATCH_ID);
        var response = mockMvc.perform(get("/api/v1/matches/{matchId}/development", DemoSeedCommand.MATCH_ID).param("focus", "6").param("compare", "1"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var focal = json.readTree(response).path("roster").get(5);
        assertThat(focal.path("runes").path("availability").stringValue()).isEqualTo("available");
        assertThat(focal.path("runes").path("styles").get(0).path("selections").get(0).path("counters").path("var1").intValue()).isEqualTo(576);
        assertThat(focal.path("runes").path("styles").get(0).path("selections").get(0).path("counters").path("var2").intValue()).isEqualTo(454);
        assertThat(focal.path("participantTotals").path("totalHeal").intValue()).isEqualTo(2000);
        assertThat(jdbc.queryForObject("select timeline_source_capture_id from league_analysis.riot_match where match_id=?", java.util.UUID.class, DemoSeedCommand.MATCH_ID)).isEqualTo(timelineCapture);
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.match_event where match_id=?", Integer.class, DemoSeedCommand.MATCH_ID)).isEqualTo(eventCount);
        assertThat(response).doesNotContain("puuid", "sourceCaptureId");
    }

    @Test
    void malformedAssisterArraysRemainUnavailableWithoutInventingParticipantIds() throws Exception {
        seedCommand.seed();
        for (var malformed : java.util.List.of("[2,null]", "[4294967298]", "[11]", "[0]", "[\"2\"]", "{}")) {
            jdbc.update("""
                    update league_analysis.match_event
                    set event_payload=jsonb_set(event_payload,'{assistingParticipantIds}',?::jsonb)
                    where match_id=? and provider_event_type='CHAMPION_KILL'
                    """, malformed, DemoSeedCommand.MATCH_ID);
            var body=mockMvc.perform(get("/api/v1/matches/{matchId}/development", DemoSeedCommand.MATCH_ID)
                            .param("focus", "6").param("compare", "1"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var event=json.readTree(body).path("events").get(1);
            assertThat(event.path("assistersObserved").booleanValue()).as(malformed).isFalse();
            assertThat(event.path("assisterParticipantIds").size()).as(malformed).isZero();
            assertThat(event.path("participantIds").toString()).isEqualTo("[1,6]");
            assertThat(event.path("fields").path("assistingParticipantIds")).isEqualTo(json.readTree(malformed));
        }
    }

    @Test
    void incompleteRosterCannotBecomeACompleteFinalTeamTotal() throws Exception {
        seedCommand.seed();
        jdbc.update("delete from league_analysis.riot_participant where match_id=? and participant_id=2", DemoSeedCommand.MATCH_ID);
        var response=mockMvc.perform(get("/api/v1/matches/{matchId}/development", DemoSeedCommand.MATCH_ID)
                        .param("focus", "6").param("compare", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var team=json.readTree(response).path("teams").get(0);
        for(var field:java.util.List.of("kills","deaths","assists","goldEarned")) assertThat(team.path(field).isNull()).isTrue();
    }

    @Test
    void ordersByExactTimeWithSourceTiesFiltersOldCaptureAndKeepsMissingTotalsNull() throws Exception {
        seedCommand.seed();
        int seededEvents = jdbc.queryForObject("select count(*) from league_analysis.match_event where match_id=?",
                Integer.class, DemoSeedCommand.MATCH_ID);
        jdbc.update("""
                update league_analysis.riot_team set objectives='{"dragon":{"kills":2},"atakhan":{"kills":0}}'::jsonb
                where match_id=? and team_id=100
                """, DemoSeedCommand.MATCH_ID);
        jdbc.update("""
                insert into league_analysis.match_event
                (id,match_id,represented_at_ms,frame_at_ms,frame_event_index,provider_event_type,canonical_event_kind,
                 actor_participant_id,event_payload,source_capture_id,method_version)
                select gen_random_uuid(),match_id,600001,600000,99,'CHAMPION_SPECIAL_KILL','OTHER',6,
                '{"type":"CHAMPION_SPECIAL_KILL","multiKillLength":2,"puuid":"never-public","token":"never-public"}'::jsonb,
                timeline_source_capture_id,'test' from league_analysis.riot_match where match_id=?
                """, DemoSeedCommand.MATCH_ID);
        jdbc.update("""
                insert into league_analysis.match_event
                (id,match_id,represented_at_ms,frame_at_ms,frame_event_index,provider_event_type,canonical_event_kind,
                 actor_participant_id,event_payload,source_capture_id,method_version)
                select gen_random_uuid(),match_id,600000,600000,100,'WARD_PLACED','WARD',6,
                '{"type":"WARD_PLACED","wardType":"CONTROL_WARD","position":{"x":5,"puuid":"never-public"}}'::jsonb,
                timeline_source_capture_id,'test' from league_analysis.riot_match where match_id=?
                """, DemoSeedCommand.MATCH_ID);
        jdbc.update("""
                insert into league_analysis.match_event
                (id,match_id,represented_at_ms,frame_at_ms,frame_event_index,provider_event_type,canonical_event_kind,
                 actor_participant_id,event_payload,source_capture_id,method_version)
                select gen_random_uuid(),match_id,600000,600000,101,'WARD_KILL','WARD',6,
                '{"type":"WARD_KILL"}'::jsonb,detail_source_capture_id,'test'
                from league_analysis.riot_match where match_id=?
                """, DemoSeedCommand.MATCH_ID);
        var response=mockMvc.perform(get("/api/v1/matches/{matchId}/development", DemoSeedCommand.MATCH_ID)
                        .param("focus", "6").param("compare", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events.length()").value(seededEvents + 2))
                .andExpect(jsonPath("$.events[4].type").value("CHAMPION_SPECIAL_KILL"))
                .andExpect(jsonPath("$.events[3].type").value("WARD_PLACED"))
                .andExpect(jsonPath("$.events[4].frameEventIndex").value(99))
                .andExpect(jsonPath("$.events[4].fields.multiKillLength").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("never-public", "puuid", "token");
        var result=json.readTree(response);
        assertThat(result.path("teams").get(0).path("objectives").path("tower").isNull()).isTrue();
        assertThat(result.path("teams").get(0).path("kills").intValue()).isEqualTo(jdbc.queryForObject(
                "select sum(kills) from league_analysis.riot_participant where match_id=? and team_id=100",
                Integer.class, DemoSeedCommand.MATCH_ID));
        assertThat(result.path("teams").get(0).path("goldEarned").intValue()).isEqualTo(jdbc.queryForObject(
                "select sum(gold_earned) from league_analysis.riot_participant where match_id=? and team_id=100",
                Integer.class, DemoSeedCommand.MATCH_ID));
    }

    @Test
    void ranksEndpointHandlesMissingMatchesAndMissingCredentialsIndependently() throws Exception {
        seedCommand.seed();
        mockMvc.perform(get("/api/v1/matches/missing/ranks")).andExpect(status().isNotFound());
        var response=mockMvc.perform(get("/api/v1/matches/{matchId}/ranks", DemoSeedCommand.MATCH_ID))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.players.length()").value(10))
                .andExpect(jsonPath("$.players[0].status").value("unavailable"))
                .andExpect(jsonPath("$.queueType").value("RANKED_SOLO_5x5"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("puuid","apiKey");
    }

    @Test
    void exposesRecordedResultsNamesAndEveryCurrentCaptureEvent() throws Exception {
        seedCommand.seed();
        jdbc.update("""
                update league_analysis.source_payload p set payload_json = jsonb_set(payload_json,
                '{info,participants,0,riotIdGameName}', '\"기록된 이름\"'::jsonb)
                from league_analysis.source_capture c, league_analysis.riot_match m
                where c.source_payload_id=p.id and m.detail_source_capture_id=c.id and m.match_id=?
                """, DemoSeedCommand.MATCH_ID);
        var body = mockMvc.perform(get("/api/v1/matches/{matchId}/development", DemoSeedCommand.MATCH_ID)
                        .param("focus", "6").param("compare", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teams.length()").value(2))
                .andExpect(jsonPath("$.roster[0].gameName").value("기록된 이름"))
                .andExpect(jsonPath("$.events[0].type").value("ITEM_PURCHASED"))
                .andExpect(jsonPath("$.events[0].fields.itemId").value(3071))
                .andReturn().getResponse().getContentAsString();
        var result = json.readTree(body);
        assertThat(result.path("events").size()).isEqualTo(jdbc.queryForObject("""
                select count(*) from league_analysis.match_event e join league_analysis.riot_match m
                on e.match_id=m.match_id and e.source_capture_id=m.timeline_source_capture_id
                where m.match_id=?
                """, Integer.class, DemoSeedCommand.MATCH_ID));
        assertThat(result.path("teams").get(0).path("objectives").has("atakhan")).isFalse();
    }

    @Test
    void seedPersistsTheExactDevelopmentApiAndRepeatingItLeavesRevisionsAndOtherMatchesAlone()
            throws Exception {
        unrelatedFixture.replaceFixtureMatch();

        assertThat(seedCommand.seed()).isEqualTo(DemoSeedCommand.SeedResult.CREATED);
        var firstCounts = counts();
        var firstRevision = revision();

        var responseBody = mockMvc.perform(get(
                            "/api/v1/matches/{matchId}/development", DemoSeedCommand.MATCH_ID)
                        .param("focus", "6")
                        .param("compare", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.summary.win").value(true))
                .andExpect(jsonPath("$.summary.kills").value(8))
                .andExpect(jsonPath("$.roster.length()").value(10))
                .andExpect(jsonPath("$.samples.length()").value(15))
                .andExpect(jsonPath("$.events[0].timestampMs").value(505_210))
                .andExpect(jsonPath("$.events[0].label").value("Purchased item 3071"))
                .andExpect(jsonPath("$.events[1].timestampMs").value(552_430))
                .andExpect(jsonPath("$.events[1].label").value("Champion kill"))
                .andExpect(jsonPath("$.events[2].timestampMs").value(589_775))
                .andExpect(jsonPath("$.events[2].label").value("Dragon secured"))
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(
                "puuid", "payload_json", "payloadJson", "sourceCapture", "raw");
        assertSample(json.readTree(responseBody), 480_000, 4, 100, 20);
        assertSample(json.readTree(responseBody), 600_000, 13, 510, 220);

        mockMvc.perform(get("/api/v1/demo"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.matchId").value(DemoSeedCommand.MATCH_ID))
                .andExpect(jsonPath("$.focusParticipantId").value(6))
                .andExpect(jsonPath("$.compareParticipantId").value(1))
                .andExpect(jsonPath("$.invented").value(true));

        assertThat(seedCommand.seed()).isEqualTo(DemoSeedCommand.SeedResult.ALREADY_PRESENT);
        assertThat(counts()).isEqualTo(firstCounts);
        assertThat(revision()).isEqualTo(firstRevision);
        assertThat(jdbc.queryForObject("""
                select count(*) from league_analysis.riot_match where match_id = ?
                """, Integer.class, P3SanitizedMatchFixture.MATCH_ID)).isEqualTo(1);
    }

    @Test
    void syntheticObjectiveTotalsMatchTheirRecordedTimelineEvents() throws Exception {
        seedCommand.seed();
        var body = mockMvc.perform(get("/api/v1/matches/{matchId}/development", DemoSeedCommand.MATCH_ID)
                        .param("focus", "6").param("compare", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var result = json.readTree(body);
        var objectives = Map.of("dragon", "DRAGON", "baron", "BARON_NASHOR", "riftHerald", "RIFTHERALD",
                "horde", "HORDE", "tower", "TOWER_BUILDING", "inhibitor", "INHIBITOR_BUILDING");
        for (var team : result.path("teams")) {
            int teamId = team.path("teamId").intValue();
            for (var objective : objectives.entrySet()) {
                int recorded = 0;
                for (var event : result.path("events")) {
                    var fields = event.path("fields");
                    if (event.path("presentation").path("actorTeam").path("teamId").asInt() == teamId
                            && (objective.getValue().equals(fields.path("monsterType").asText())
                                || objective.getValue().equals(fields.path("buildingType").asText()))) recorded++;
                }
                var total = team.path("objectives").path(objective.getKey());
                assertThat(total.isIntegralNumber()).as("team %s %s availability", teamId, objective.getKey()).isTrue();
                assertThat(total.intValue()).as("team %s %s count", teamId, objective.getKey()).isEqualTo(recorded);
            }
        }
    }

    @Test
    void refusesToReplaceTheReservedIdWhenItContainsDifferentSourceData() throws Exception {
        seedDifferentReservedMatch();
        var before = counts();

        assertThatThrownBy(seedCommand::seed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DEMO_MATCH_ID_CONFLICT");

        assertThat(counts()).isEqualTo(before);
        mockMvc.perform(get("/api/v1/demo"))
                .andExpect(status().isNotFound());
    }

    private void seedDifferentReservedMatch() throws Exception {
        var detail = fixture("match.json").deepCopy();
        ((ObjectNode) detail.path("info")).put("gameVersion", "different-source-version");
        var now = Instant.parse("2026-09-09T12:00:00Z");
        var runId = store.startRun(new RiotIngestionCommand("Reserved", "TEST", 1), now);
        store.addItems(runId, java.util.List.of(DemoSeedCommand.MATCH_ID));
        store.markItemRunning(runId, DemoSeedCommand.MATCH_ID, now);
        var capture = store.saveCapture(runId, document(SourceKind.MATCH_DETAIL, detail, now));
        var materialization = new MatchV5Decoder().decode(capture, Optional.empty());
        store.materialize(runId, DemoSeedCommand.MATCH_ID, materialization);
        store.markItemTerminal(
                runId, DemoSeedCommand.MATCH_ID, IngestionItemStatus.PARTIAL, null, null, now);
        store.finishRun(runId, IngestionRunStatus.PARTIAL, null, null, now);
    }

    private ProviderDocument document(SourceKind kind, JsonNode payload, Instant capturedAt)
            throws Exception {
        var encoded = payload.toString().getBytes(StandardCharsets.UTF_8);
        return new ProviderDocument(
                kind,
                DemoSeedCommand.MATCH_ID,
                capturedAt,
                200,
                "AMERICAS",
                "NA1",
                "16.17.1",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)),
                encoded.length,
                payload,
                json.createObjectNode(),
                "match-v5-v1",
                1);
    }

    private JsonNode fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/demo/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing fixture: " + name);
            }
            return json.readTree(input);
        }
    }

    private Map<String, Integer> counts() {
        return Map.of(
                "matches", count("riot_match"),
                "captures", count("source_capture"),
                "observations", count("participant_state_observation"),
                "events", count("match_event"));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from league_analysis." + table, Integer.class);
    }

    private Map<String, Object> revision() {
        return jdbc.queryForMap("""
                select detail_source_capture_id, timeline_source_capture_id, materialization_version
                from league_analysis.riot_match where match_id = ?
                """, DemoSeedCommand.MATCH_ID);
    }

    private void assertSample(
            JsonNode response,
            long timestampMs,
            int expectedCsDifference,
            int expectedGoldDifference,
            int expectedXpDifference) {
        var sample = java.util.stream.StreamSupport.stream(
                        response.path("samples").spliterator(), false)
                .filter(candidate -> candidate.path("timestampMs").longValue() == timestampMs)
                .findFirst()
                .orElseThrow();
        assertThat(sample.path("csDifference").intValue()).isEqualTo(expectedCsDifference);
        assertThat(sample.path("goldDifference").intValue()).isEqualTo(expectedGoldDifference);
        assertThat(sample.path("xpDifference").intValue()).isEqualTo(expectedXpDifference);
    }
}
