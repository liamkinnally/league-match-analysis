package dev.leagueanalysis.ingestion.riot.adapter.in.web;

import dev.leagueanalysis.ingestion.riot.application.IngestionRunStatus;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionCommand;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionResult;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = RiotIngestionController.class,
        properties = "spring.config.name=task6-test")
@ActiveProfiles("local")
class RiotIngestionControllerTest {
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired MockMvc mvc;
    @MockitoBean RiotIngestionService service;

    @Test
    void startsSynchronousIngestionAndReturnsOnlyItsAggregateResult() throws Exception {
        when(service.ingest(any())).thenReturn(
                new RiotIngestionResult(RUN_ID, IngestionRunStatus.COMPLETE, 5, 5, 0, 0));

        var response = mvc.perform(post("/api/local/riot/ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gameName":"ExamplePlayer","tagLine":"NA1","matchLimit":5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.requested").value(5))
                .andExpect(jsonPath("$.complete").value(5))
                .andExpect(jsonPath("$.partial").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andReturn().getResponse().getContentAsString();

        verify(service).ingest(new RiotIngestionCommand("ExamplePlayer", "NA1", 5));
        assertThat(response).doesNotContain(
                "puuid", "source", "header", "http://", "https://", "stack",
                "regionalRoute", "platformRoute", "queueId", "apiKey");
    }

    @Test
    void rejectsInvalidInputWithAStableSanitizedError() throws Exception {
        var response = mvc.perform(post("/api/local/riot/ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gameName":" ","tagLine":"NA1","matchLimit":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(
                "puuid", "source", "header", "http://", "https://", "stack",
                "regionalRoute", "platformRoute", "queueId", "apiKey");
    }

    @Test
    void rejectsCallerSuppliedRoutingAndQueueOverrides() throws Exception {
        mvc.perform(post("/api/local/riot/ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "gameName":"ExamplePlayer",
                                  "tagLine":"NA1",
                                  "matchLimit":1,
                                  "regionalRoute":"EUROPE",
                                  "platformRoute":"EUW1",
                                  "queueId":430
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }
}
