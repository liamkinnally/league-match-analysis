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
