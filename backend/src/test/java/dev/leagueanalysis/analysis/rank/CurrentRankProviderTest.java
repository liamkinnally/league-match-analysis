package dev.leagueanalysis.analysis.rank;

import static org.assertj.core.api.Assertions.*;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.RiotHttpTransport;
import dev.leagueanalysis.ingestion.riot.config.RiotProperties;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CurrentRankProviderTest {
    @Test void ancillaryRankQueueCannotDiscardValidSoloAndFlexEntries() {
        var provider=refreshedProvider("""
            [{"queueType":"RANKED_SOLO_5x5","tier":"GOLD","rank":"II","leaguePoints":37,"wins":6,"losses":4},
             {"queueType":"JADE_RANKED_SOLO_5x5","tier":"SALT","rank":"III","leaguePoints":7,"wins":1,"losses":2},
             {"queueType":"RANKED_FLEX_SR","tier":"SILVER","rank":"I","leaguePoints":23,"wins":5,"losses":9}]
            """);
        var solo=provider.peek("NA1","invented","RANKED_SOLO_5x5");
        assertThat(solo.error()).isNull();
        assertThat(solo.value()).isEqualTo(new RankSnapshotStore.Value("ranked","GOLD","II",37,6,4));
        assertThat(solo.fetchedAt()).isEqualTo(Instant.ofEpochMilli(1000000L));
        var flex=provider.peek("NA1","invented","RANKED_FLEX_SR");
        assertThat(flex.value()).isEqualTo(new RankSnapshotStore.Value("ranked","SILVER","I",23,5,9));
        assertThat(flex.fetchedAt()).isEqualTo(solo.fetchedAt());
    }

    @Test void successfulResponseWithOnlyAncillaryQueuesMeansKnownQueuesAreUnranked() {
        var provider=refreshedProvider("""
            [{"queueType":"JADE_RANKED_SOLO_5x5","tier":"SALT","rank":"III","leaguePoints":7}]
            """);
        for(var queue:List.of("RANKED_SOLO_5x5","RANKED_FLEX_SR")) {
            var state=provider.peek("NA1","invented",queue);
            assertThat(state.error()).isNull();
            assertThat(state.value()).isEqualTo(new RankSnapshotStore.Value("unranked",null,null,null,null,null));
            assertThat(state.fetchedAt()).isNotNull();
        }
    }

    @Test void unsupportedTierInRecognizedQueueStillRejectsTheResponse() {
        var provider=refreshedProvider("""
            [{"queueType":"RANKED_SOLO_5x5","tier":"GOLD","rank":"II","leaguePoints":37},
             {"queueType":"RANKED_FLEX_SR","tier":"SALT","rank":"III","leaguePoints":7}]
            """);
        var state=provider.peek("NA1","invented","RANKED_SOLO_5x5");
        assertThat(state.value()).isNull();
        assertThat(state.fetchedAt()).isNull();
        assertThat(state.error()).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    private CurrentRankProvider refreshedProvider(String body) {
        var work=new ArrayDeque<Runnable>();
        var properties=new RiotProperties("invented-key","AMERICAS","NA1",420,10000,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(1));
        var provider=new CurrentRankProvider(null,properties,(request,timeout,max)->
            new RiotHttpTransport.Response(200,HttpHeaders.of(Map.of(),(a,b)->true),body.getBytes(StandardCharsets.UTF_8)),
            new ObjectMapper(),()->1000000L,work::add);
        provider.refresh("NA1","invented","RANKED_SOLO_5x5");
        work.remove().run();
        return provider;
    }

    @Test void cachedProfileReadsNeverAdmitProviderCallsAndMissingWinLossStaysUnknown() {
        var work=new ArrayDeque<Runnable>();var calls=new AtomicInteger();
        var properties=new RiotProperties("invented-key","AMERICAS","NA1",420,10000,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(1));
        var provider=new CurrentRankProvider(null,properties,(request,timeout,max)->{
            calls.incrementAndGet();return new RiotHttpTransport.Response(200,HttpHeaders.of(Map.of(),(a,b)->true),"[{\"queueType\":\"RANKED_SOLO_5x5\",\"tier\":\"GOLD\",\"rank\":\"I\",\"leaguePoints\":5}]".getBytes(StandardCharsets.UTF_8));
        },new ObjectMapper(),()->1000000L,work::add);
        for(int i=0;i<20;i++)assertThat(provider.peek("NA1","invented","RANKED_SOLO_5x5").value()).isNull();
        assertThat(work).isEmpty();assertThat(calls).hasValue(0);
        provider.refresh("NA1","invented","RANKED_SOLO_5x5");work.remove().run();
        var value=provider.peek("NA1","invented","RANKED_SOLO_5x5").value();
        assertThat(value.wins()).isNull();assertThat(value.losses()).isNull();
        for(int i=0;i<20;i++)provider.peek("NA1","invented","RANKED_FLEX_SR");
        assertThat(calls).hasValue(1);assertThat(work).isEmpty();
    }
}
