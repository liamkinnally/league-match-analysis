package dev.leagueanalysis.analysis.leaderboard;

import dev.leagueanalysis.ingestion.riot.config.RiotProperties;
import jakarta.annotation.*;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public final class LeaderboardService {
    private final LeaderboardStore store;
    private final LeaderboardClient client;
    private final Clock clock;
    private final boolean enabled;
    private final Duration lease;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"leaderboard-refresh");t.setDaemon(true);return t;});
    public LeaderboardService(LeaderboardStore store,LeaderboardClient client,Clock clock,RiotProperties properties,
            @Value("${league-analysis.riot.public-lookup-enabled:false}") boolean publicLookup,
            @Value("${league-analysis.leaderboard.enabled:true}") boolean enabled) {
        this.store=store;this.client=client;this.clock=clock;this.enabled=enabled&&publicLookup&&!properties.apiKey().isBlank();
        this.lease=properties.requestTimeout().plusSeconds(120);
    }
    public Leaderboard read(){return store.read(clock.instant(),enabled);}
    @PostConstruct void start(){if(enabled)timer.scheduleWithFixedDelay(this::safeTick,1,1,TimeUnit.SECONDS);}
    private void safeTick(){try{tick();}catch(RuntimeException ignored){/* Retry on the next bounded turn; never log provider data. */}}
    void tick(){
        if(!enabled)return;
        var work=store.claim(clock.instant(),lease).orElse(null);if(work==null)return;
        try {
            if(work.tier()>=0)store.ladder(work,client.ladder(work.tier()),clock.instant());
            else if(work.icon())store.member(work,null,client.icon(work.puuid()),clock.instant());
            else store.member(work,client.name(work.puuid()),null,clock.instant());
        } catch(LeaderboardClient.Failure failure){store.failure(work,failure.retry,failure.global,clock.instant());}
    }
    @PreDestroy void close(){timer.shutdownNow();}
}
