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
    @Test void absentSelectedPlatformAccountCannotCreateAnIdentityOrListHistory() {
        var wrongPlatform=spy(gateway);
        doThrow(new RiotGatewayException(RiotFailureCode.NOT_FOUND,"Not on selected platform"))
                .when(wrongPlatform).verifyPlatformAccount(anyString());
        var work=new PublicHistoryWork(run,command,wrongPlatform,store,pages,new MatchV5Decoder(),clock);
        assertThat(work.step()).isFalse();
        assertThat(work.step()).isTrue();
        verify(store,never()).saveCapture(any(),any());
        verify(store,never()).recordResolvedAccount(any(),any(),any());
        verify(wrongPlatform,never()).listMatchIds(anyString(),anyInt(),anyInt(),anyInt(),any());
        verify(store).finishRun(run,IngestionRunStatus.FAILED,"NOT_FOUND","History lookup could not finish",clock.instant());
    }

    @Test void verifiedEmptyAccountRetainsProfileAndDoesNotRepeatAccountAfterVerificationCooldown() {
        var verified=spy(gateway); verified.empty=true;
        doThrow(new RiotGatewayException(RiotFailureCode.RATE_LIMITED,"Cooling down",clock.instant().plusSeconds(30)))
                .doReturn(new PlatformAccountProfile(29,180L,null)).when(verified).verifyPlatformAccount(anyString());
        var work=new PublicHistoryWork(run,command,verified,store,pages,new MatchV5Decoder(),clock);
        assertThat(work.step()).isFalse();
        assertThatThrownBy(work::step).isInstanceOf(RiotGatewayException.class);
        assertThat(work.step()).isFalse();
        assertThat(work.step()).isFalse();
        assertThat(work.step()).isTrue();
        verify(verified,times(1)).resolveAccount(any());
        verify(store).recordVerifiedProfile(run,new PlatformAccountProfile(29,180L,null),clock.instant());
        verify(store).finishRun(run,IngestionRunStatus.COMPLETE,null,null,clock.instant());
    }

    @Test void transferredHistoryFiltersOtherPlatformsWithoutChangingProviderPageSizeOrSavingMixedList() {
        var mixed=spy(gateway);
        var ids=new ArrayList<String>(); ids.add("EUW1_7000000002");
        for(int i=1;i<20;i++)ids.add("TR1_"+i);
        var doc=gateway.listMatchIds("invented",420,0,20,null).source();
        doReturn(new RiotMatchList(ids,doc)).when(mixed).listMatchIds(anyString(),anyInt(),anyInt(),anyInt(),any());
        doReturn(gateway.fetchMatchDetail(PublicLookupGatewayFixture.MATCH_ID)).when(mixed).fetchMatchDetail(anyString());
        when(pages.hasSummary("EUW1_7000000002",PublicLookupGatewayFixture.PUUID,420)).thenReturn(true);
        var regional=new RiotIngestionCommand("Invented","tag",20,420,0,clock.instant().getEpochSecond(),null,"EUW1");
        var work=new PublicHistoryWork(run,regional,mixed,store,pages,new MatchV5Decoder(),clock);
        while(!work.step()) {}
        verify(pages).recordPageSize(run,20);
        verify(store).addItems(run,List.of("EUW1_7000000002"));
        verify(store,never()).saveCapture(eq(run),argThat(source->source.kind()==SourceKind.MATCH_LIST));
        verify(mixed,never()).fetchMatchDetail(startsWith("TR1_"));
    }

    @Test void eachTurnMakesAtMostOneProviderCallAndHistoryNeverFetchesTimeline() {
        var work = new RiotIngestionService(gateway, new MatchV5Decoder(), store, clock).historyWork(run, command, pages);
        assertThat(work.step()).isFalse();
        assertThat(gateway.calls).containsExactly("account");
        assertThat(work.step()).isFalse(); // Platform membership verification is its own scheduler turn.
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
        work.step(); work.step(); work.step();
        assertThatThrownBy(work::step).isInstanceOf(RiotGatewayException.class);
        assertThat(work.step()).isFalse();
        assertThat(work.step()).isTrue();
        assertThat(gateway.calls).containsExactly("account", "list", "detail");
        verify(store, never()).markItemTerminal(eq(run), anyString(), eq(IngestionItemStatus.FAILED), any(), any(), any());
    }
}
