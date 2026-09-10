package dev.leagueanalysis.analysis.match.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.leagueanalysis.analysis.match.application.AnalysisRequest;
import dev.leagueanalysis.analysis.match.application.HistoricalMatchQuery;
import dev.leagueanalysis.analysis.match.application.MatchAnalysisException;
import dev.leagueanalysis.analysis.match.application.MatchAnalysisService;
import dev.leagueanalysis.analysis.match.application.MatchAnalysisTestFixture;
import dev.leagueanalysis.analysis.match.domain.LensKind;
import dev.leagueanalysis.analysis.match.domain.MatchAnalysis;
import dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = MatchAnalysisController.class,
        properties = "spring.config.name=task4-test")
@ActiveProfiles("local")
class MatchAnalysisControllerTest {
    private static final String MATCH_ID = MatchAnalysisTestFixture.MATCH_ID;

    @Autowired MockMvc mockMvc;
    @MockitoBean MatchAnalysisService service;

    private MatchAnalysis calm;

    @BeforeEach
    void buildSanitizedAnalysis() {
        calm = realAnalysis(new AnalysisRequest(
                MATCH_ID, 6, null, null, null, null, null, null));
    }

    @Test
    void returns_the_frozen_calm_response_shape_without_cache_or_raw_payload() throws Exception {
        when(service.analyze(any())).thenReturn(Optional.of(calm));

        var body = mockMvc.perform(get("/api/matches/{matchId}/analysis", MATCH_ID)
                        .param("focalParticipantId", "6"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.match.matchId").value(MATCH_ID))
                .andExpect(jsonPath("$.match.focalParticipant.participantId").value(6))
                .andExpect(jsonPath("$.evidenceRevision").value(calm.evidenceRevision()))
                .andExpect(jsonPath("$.arc.selectionRuleVersion")
                        .value("material-transition-p3-v1"))
                .andExpect(jsonPath("$.arc.transitions[0].primaryLens").value("MAP"))
                .andExpect(jsonPath("$.arc.transitions[1].primaryLens").value("SEQUENCE"))
                .andExpect(jsonPath("$.arc.transitions[2].primaryLens").value("STATE"))
                .andExpect(jsonPath("$.arc.transitions[3].primaryLens").value("SEQUENCE"))
                .andExpect(jsonPath("$.review.episodes.length()").value(4))
                .andExpect(jsonPath("$.review.learningOrder.length()").value(4))
                .andExpect(jsonPath("$.active").value(Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(
                "payload_json", "payloadJson", "puuid", "riotId", "gameName", "tagLine");
    }

    @Test
    void returns_selected_receipt_timestamps_discriminated_lens_and_safe_evidence()
            throws Exception {
        var packet = calm.arc().transitions().get(1);
        var request = new AnalysisRequest(
                MATCH_ID, 6, packet.transitionId(),
                packet.interval().startMs(), packet.interval().endMs(),
                packet.questionKind().questionId(), calm.evidenceRevision(),
                LensKind.SEQUENCE);
        var selected = realAnalysis(request);
        when(service.analyze(any())).thenReturn(Optional.of(selected));

        var body = mockMvc.perform(get("/api/matches/{matchId}/analysis", MATCH_ID)
                        .param("focalParticipantId", "6")
                        .param("objectId", packet.transitionId())
                        .param("intervalStartMs", Long.toString(packet.interval().startMs()))
                        .param("intervalEndMs", Long.toString(packet.interval().endMs()))
                        .param("questionId", packet.questionKind().questionId())
                        .param("evidenceRevision", calm.evidenceRevision())
                        .param("requestedLens", "SEQUENCE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.active.context.selectedObjectId")
                        .value(packet.transitionId()))
                .andExpect(jsonPath("$.active.context.primaryLens").value("SEQUENCE"))
                .andExpect(jsonPath("$.active.lens.type").value("SEQUENCE"))
                .andExpect(jsonPath("$.active.receipt.before.representedAtMs")
                        .value(packet.receipt().beforeRepresentedAtMs()))
                .andExpect(jsonPath("$.active.receipt.after.representedAtMs")
                        .value(packet.receipt().afterRepresentedAtMs()))
                .andExpect(jsonPath("$.active.claims[0].claimId").isNotEmpty())
                .andExpect(jsonPath("$.active.claims[0].assertionMode").isNotEmpty())
                .andExpect(jsonPath("$.active.evidence.references[0].sourceKind")
                        .value("MATCH_TIMELINE"))
                .andExpect(jsonPath("$.active.evidence.references[0].sourceCaptureId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.active.evidence.references[0].sourceRecordId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.active.evidence.references[0].representedAtMs")
                        .isNumber())
                .andExpect(jsonPath("$.active.evidence.references[0].methodVersion")
                        .isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(
                "payload_json", "payloadJson", "puuid", "riotId", "gameName", "tagLine");
    }

    @Test
    void rejects_invalid_focal_partial_context_malformed_object_and_unknown_lens()
            throws Exception {
        assertInvalid(get("/api/matches/{matchId}/analysis", MATCH_ID)
                .param("focalParticipantId", "0"));
        assertInvalid(get("/api/matches/{matchId}/analysis", MATCH_ID)
                .param("focalParticipantId", "6")
                .param("objectId", "trn_" + "a".repeat(24))
                .param("intervalStartMs", "100"));
        assertInvalid(get("/api/matches/{matchId}/analysis", MATCH_ID)
                .param("focalParticipantId", "6")
                .param("objectId", "not-a-logical-id")
                .param("intervalStartMs", "100")
                .param("intervalEndMs", "200")
                .param("questionId", "mixed-value")
                .param("evidenceRevision", "ev_" + "0".repeat(64))
                .param("requestedLens", "RECEIPT"));
        assertInvalid(get("/api/matches/{matchId}/analysis", MATCH_ID)
                .param("focalParticipantId", "6")
                .param("objectId", "trn_" + "a".repeat(24))
                .param("intervalStartMs", "100")
                .param("intervalEndMs", "200")
                .param("questionId", "mixed-value")
                .param("evidenceRevision", "ev_" + "0".repeat(64))
                .param("requestedLens", "NOT_A_LENS"));

        verifyNoInteractions(service);
    }

    @Test
    void returns_exact_not_found_codes() throws Exception {
        when(service.analyze(any())).thenReturn(Optional.empty());
        assertNotFound("MATCH_NOT_FOUND");

        doThrow(MatchAnalysisException.focalParticipantNotFound())
                .when(service).analyze(any());
        assertNotFound("FOCAL_PARTICIPANT_NOT_FOUND");

        doThrow(MatchAnalysisException.analyticalObjectNotFound())
                .when(service).analyze(any());
        assertNotFound("ANALYTICAL_OBJECT_NOT_FOUND");
    }

    @Test
    void returns_exact_stale_revision_conflict_contract() throws Exception {
        when(service.analyze(any())).thenThrow(
                MatchAnalysisException.staleRevision(calm.evidenceRevision()));

        mockMvc.perform(get("/api/matches/{matchId}/analysis", MATCH_ID)
                        .param("focalParticipantId", "6"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("STALE_EVIDENCE_REVISION"))
                .andExpect(jsonPath("$.message").value(
                        "The match evidence changed. Refresh this analysis before continuing."))
                .andExpect(jsonPath("$.currentEvidenceRevision")
                        .value(calm.evidenceRevision()));
    }

    @Test
    void exposes_only_the_response_dto_from_the_http_method() throws Exception {
        assertThat(java.util.Arrays.stream(MatchAnalysisController.class.getMethods())
                        .filter(method -> method.getName().equals("analyze"))
                        .findFirst()
                        .orElseThrow()
                        .getReturnType())
                .isEqualTo(MatchAnalysisResponse.class);
    }

    private void assertInvalid(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_ANALYSIS_REQUEST"));
    }

    private void assertNotFound(String code) throws Exception {
        mockMvc.perform(get("/api/matches/{matchId}/analysis", MATCH_ID)
                        .param("focalParticipantId", "6"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(code));
    }

    private MatchAnalysis realAnalysis(AnalysisRequest request) {
        HistoricalMatchQuery query = new HistoricalMatchQuery() {
            @Override
            public Optional<MatchEvidenceSnapshot> load(String matchId) {
                return Optional.of(MatchAnalysisTestFixture.snapshot());
            }
        };
        return new MatchAnalysisService(query).analyze(request).orElseThrow();
    }
}
