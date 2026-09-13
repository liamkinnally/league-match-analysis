package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.domain.*;
import dev.leagueanalysis.support.PublicLookupGatewayFixture;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicHistoryWorkTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC);
    final RiotIngestionStore store = mock(RiotIngestionStore.class);
    final PublicMatchLookupStore pages = mock(PublicMatchLookupStore.class);
    final PublicLookupGatewayFixture gateway = new PublicLookupGatewayFixture(new ObjectMapper(), clock);
    final UUID run = UUID.randomUUID();
    final RiotIngestionCommand command = new RiotIngestionCommand("Invented", "NA1", 20, 420, 0, clock.instant().getEpochSecond(), null);

    PublicHistoryWorkTest() {
        when(store.saveCapture(eq(run), any())).thenAnswer(c -> new CapturedDocument(UUID.randomUUID(), UUID.randomUUID(), c.getArgument(1)));
        doAnswer(call -> {
            when(pages.hasSummary(eq(call.getArgument(1)), anyString(), eq(420))).thenReturn(true);
            return null;
        }).when(store).materialize(eq(run), anyString(), any());
    }
    @Test void eachTurnMakesAtMostOneProviderCallAndHistoryNeverFetchesTimeline() {
        var work = new RiotIngestionService(gateway, new MatchV5Decoder(), store, clock).historyWork(run, command, pages);
        assertThat(work.step()).isFalse();
        assertThat(gateway.calls).containsExactly("account");
        assertThat(work.step()).isFalse();
        assertThat(gateway.calls).containsExactly("account", "list");
        assertThat(work.step()).isFalse();
        assertThat(gateway.calls).containsExactly("account", "list", "detail");
        assertThat(work.step()).isTrue();
        verify(store).materialize(eq(run), eq(PublicLookupGatewayFixture.MATCH_ID), argThat(m -> m.match().timelineSourceCaptureId() == null));
        verify(store).finishRun(run, IngestionRunStatus.COMPLETE, null, null, clock.instant());
    }
    @Test void storedSummaryAvoidsBothDetailAndTimelineCalls() {
        when(pages.hasSummary(anyString(), anyString(), eq(420))).thenReturn(true);
        var work = new RiotIngestionService(gateway, new MatchV5Decoder(), store, clock).historyWork(run, command, pages);
        while (!work.step()) {}
        assertThat(gateway.calls).containsExactly("account", "list");
        verify(store, never()).materialize(any(), any(), any());
    }
    @Test void rateLimitLeavesCurrentStepRetryableWithoutRefetchingAccount() {
        var limited = spy(gateway);
        doThrow(new RiotGatewayException(RiotFailureCode.RATE_LIMITED, "private", clock.instant().plusSeconds(120)))
                .doCallRealMethod().when(limited).fetchMatchDetail(anyString());
        var work = new RiotIngestionService(limited, new MatchV5Decoder(), store, clock).historyWork(run, command, pages);
        work.step(); work.step();
        assertThatThrownBy(work::step).isInstanceOf(RiotGatewayException.class);
        assertThat(work.step()).isFalse();
        assertThat(work.step()).isTrue();
        assertThat(gateway.calls).containsExactly("account", "list", "detail");
        verify(store, never()).markItemTerminal(eq(run), anyString(), eq(IngestionItemStatus.FAILED), any(), any(), any());
    }
}
