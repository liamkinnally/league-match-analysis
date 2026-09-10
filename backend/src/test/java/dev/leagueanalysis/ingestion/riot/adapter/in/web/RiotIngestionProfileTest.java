package dev.leagueanalysis.ingestion.riot.adapter.in.web;

import dev.leagueanalysis.ingestion.riot.application.RiotIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RiotIngestionController.class)
class RiotIngestionProfileTest {
    @Autowired MockMvc mvc;
    @MockitoBean RiotIngestionService service;

    @Test
    void endpointIsAbsentOutsideTheLocalProfile() throws Exception {
        mvc.perform(post("/api/local/riot/ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gameName":"ExamplePlayer","tagLine":"NA1","matchLimit":5}
                                """))
                .andExpect(status().isNotFound());
    }
}
