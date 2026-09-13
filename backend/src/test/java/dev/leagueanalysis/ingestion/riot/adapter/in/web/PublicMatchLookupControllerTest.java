package dev.leagueanalysis.ingestion.riot.adapter.in.web;

import dev.leagueanalysis.ingestion.riot.application.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicMatchLookupControllerTest {
    @Test void returnsAcceptedCachedValidationAndCooldownStatusesWithoutPrivateData() throws Exception {
        var service = mock(PublicMatchLookupService.class);
        var now = Instant.parse("2026-09-09T12:00:00Z");
        var mvc = MockMvcBuilders.standaloneSetup(new PublicMatchLookupController(service,
                Clock.fixed(now, ZoneOffset.UTC))).build();
        var lookup = new PublicMatchLookup(UUID.randomUUID(), "Invented", "NA1", "RUNNING", null, null, List.of());
        when(service.submit(any(), any(), anyInt(), any())).thenReturn(new PublicMatchLookupService.Submission(202, lookup));
        mvc.perform(post("/api/v1/player-matches").contentType("application/json")
                .content("{\"gameName\":\"Invented\",\"tagLine\":\"NA1\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.runId").value(lookup.runId().toString())).andExpect(jsonPath("$.resolvedPuuid").doesNotExist());
        when(service.submit(any(), any(), anyInt(), any())).thenReturn(new PublicMatchLookupService.Submission(200, lookup));
        mvc.perform(post("/api/v1/player-matches").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/player-matches").contentType("application/json").content("not-json"))
                .andExpect(status().isBadRequest());
        when(service.submit(any(), any(), anyInt(), any())).thenThrow(new PublicLookupException(429, "Cooling down", now.plusSeconds(120)));
        mvc.perform(post("/api/v1/player-matches").contentType("application/json").content("{}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "120"));
        doThrow(new PublicLookupException(503, "Unavailable", null)).when(service).submit(any(), any(), anyInt(), any());
        mvc.perform(post("/api/v1/player-matches").contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test void omittedQueueDefaultsToAllSupportedQueues() {
        var service = mock(PublicMatchLookupService.class);
        var lookup = new PublicMatchLookup(UUID.randomUUID(), "Invented", "NA1", "RUNNING", null, null, List.of());
        when(service.submit(any(), any(), anyInt(), any())).thenReturn(new PublicMatchLookupService.Submission(202, lookup));
        var controller = new PublicMatchLookupController(service, Clock.systemUTC());
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        controller.submit(new PublicMatchLookupController.Request("Invented", "NA1"), request);
        verify(service).submit("Invented", "NA1", 0, "127.0.0.1");
    }

    @Test void untrustedForwardedHeadersNeverChangeTheSharedSocketPeerBudget() {
        var service = mock(PublicMatchLookupService.class);
        var controller = new PublicMatchLookupController(service, Clock.systemUTC());
        when(service.submit("Invented", "NA1", 0, "127.0.0.1")).thenThrow(
                new PublicLookupException(429, "Shared ingress budget exhausted", null));
        for (String spoofed : List.of("198.51.100.1", "203.0.113.2")) {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-Forwarded-For", spoofed);
            request.addHeader("Forwarded", "for=" + spoofed);
            request.addHeader("X-Client-Id", spoofed);
            assertThatThrownBy(() -> controller.submit(new PublicMatchLookupController.Request("Invented", "NA1"), request))
                    .isInstanceOfSatisfying(PublicLookupException.class, error -> assertThat(error.status()).isEqualTo(429));
        }
        verify(service, times(2)).submit("Invented", "NA1", 0, "127.0.0.1");
    }
}
