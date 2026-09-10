package dev.leagueanalysis.ingestion.riot.application;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicMatchLookupServiceTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);
    final PublicMatchLookupStore store = mock(PublicMatchLookupStore.class);
    final RiotIngestionService ingestion = mock(RiotIngestionService.class);
    final Queue<Runnable> work = new ArrayDeque<>();
    final PublicMatchLookupService service = new PublicMatchLookupService(ingestion, store, clock, true, work::add);

    PublicMatchLookupServiceTest() {
        when(store.latestCooldown()).thenReturn(Optional.empty());
        when(store.findFresh(any(), any())).thenReturn(Optional.empty());
        when(store.startPublicRun(any(), any())).thenAnswer(call -> {
            UUID id = UUID.randomUUID();
            var command = (RiotIngestionCommand) call.getArgument(0);
            when(store.readPublicRun(id)).thenReturn(Optional.of(new PublicMatchLookup(id,
                    command.gameName(), command.tagLine(), "RUNNING", null, null, List.of())));
            return id;
        });
    }

    @Test void returnsRunBeforeWorkAndSharesIdenticalActiveRequests() {
        var first = service.submit(" Invented ", "NA1", "peer");
        var second = service.submit("invented", "na1", "peer");
        assertThat(first.httpStatus()).isEqualTo(202);
        assertThat(second.lookup().runId()).isEqualTo(first.lookup().runId());
        assertThat(work).hasSize(1);
        verifyNoInteractions(ingestion);
        work.remove().run();
        verify(ingestion).executePublic(first.lookup().runId(), new RiotIngestionCommand("Invented", "NA1", 5));
    }

    @Test void allowsOnlyOneWorkerAndFourWaitingAndReleasesFinishedSlot() {
        for (int i = 0; i < 5; i++) assertThat(service.submit("Player" + i, "NA1", "peer" + i).httpStatus()).isEqualTo(202);
        assertThatThrownBy(() -> service.submit("Sixth", "NA1", "sixth"))
                .isInstanceOfSatisfying(PublicLookupException.class, e -> assertThat(e.status()).isEqualTo(429));
        work.remove().run();
        assertThat(service.submit("Sixth", "NA1", "sixth").httpStatus()).isEqualTo(202);
    }

    @Test void usesFreshSuccessfulOrEmptyRunWithoutWorkButConsultsCooldownFirst() {
        UUID id = UUID.randomUUID();
        var empty = new PublicMatchLookup(id, "Nobody", "NA1", "EMPTY", null, null, List.of());
        when(store.findFresh(any(), eq(clock.instant().minusSeconds(900)))).thenReturn(Optional.of(empty));
        assertThat(service.submit("Nobody", "NA1", "peer").httpStatus()).isEqualTo(200);
        assertThat(work).isEmpty();
        when(store.latestCooldown()).thenReturn(Optional.of(clock.instant().plusSeconds(120)));
        assertThatThrownBy(() -> service.submit("Nobody", "NA1", "peer"))
                .isInstanceOfSatisfying(PublicLookupException.class, e -> {
                    assertThat(e.status()).isEqualTo(429);
                    assertThat(e.retryNotBefore()).isEqualTo(clock.instant().plusSeconds(120));
                });
    }

    @Test void boundsNewSubmissionsPerSocketPeerAndDisabledLookupDoesNoWork() {
        for (int i = 0; i < 6; i++) { service.submit("Player" + i, "NA1", "peer"); work.remove().run(); }
        assertThatThrownBy(() -> service.submit("Seventh", "NA1", "peer"))
                .isInstanceOf(PublicLookupException.class);
        var disabled = new PublicMatchLookupService(ingestion, store, clock, false, work::add);
        assertThatThrownBy(() -> disabled.submit("Player", "NA1", "other"))
                .isInstanceOfSatisfying(PublicLookupException.class, e -> assertThat(e.status()).isEqualTo(503));
    }

    @Test void distinctNameAndTagPairsCannotCollideInActiveDeduplication() {
        var first = service.submit("A#B", "C", "peer");
        var second = service.submit("A", "B#C", "peer");
        assertThat(second.lookup().runId()).isNotEqualTo(first.lookup().runId());
    }

    @Test void rejectsControlCharactersBeforeCreatingWork() {
        assertThatThrownBy(() -> service.submit("Bad\u0000Name", "NA1", "peer"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(work).isEmpty();
    }

    @Test void startupFailsInterruptedPublicRunsAndRetainsRows() {
        service.interruptPreviousRuns();
        verify(store).failInterrupted(clock.instant());
    }
}
