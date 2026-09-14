package dev.leagueanalysis.analysis.rank;

import dev.leagueanalysis.ingestion.riot.adapter.out.riot.RiotHttpTransport;
import dev.leagueanalysis.ingestion.riot.config.RiotProperties;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class CurrentRankService {
    private final RankRosterQuery rosters;
    private final RiotProperties properties;
    private final CurrentRankProvider provider;
    @Autowired public CurrentRankService(RankRosterQuery rosters,RiotProperties properties,CurrentRankProvider provider) {
        this.rosters=rosters;this.properties=properties;this.provider=provider;
    }
    CurrentRankService(RankRosterQuery rosters,RiotProperties properties,RiotHttpTransport transport,ObjectMapper json,LongSupplier clock,Executor executor) {
        this(rosters,properties,new CurrentRankProvider(null,properties,transport,json,clock,executor));
    }
    public Optional<Result> load(String matchId) {
        return rosters.load(matchId).map(roster->{
            String queue=switch(roster.queueId()){case 420->"RANKED_SOLO_5x5";case 440->"RANKED_FLEX_SR";default->null;};
            var players=new ArrayList<PlayerRank>();boolean refreshing=false;
            for(var player:roster.players()) {
                String error=queue==null?"UNSUPPORTED_QUEUE":!matchId.startsWith(properties.platformRoute()+"_")?"UNSUPPORTED_PLATFORM":null;
                if(error!=null){players.add(new PlayerRank(player.participantId(),"unavailable",null,null,null,null,false,false,error));continue;}
                var state=provider.refresh(properties.platformRoute(),player.puuid(),queue);var v=state.value();
                players.add(new PlayerRank(player.participantId(),v==null?state.refreshing()?"loading":"unavailable":v.status(),
                    v==null?null:v.tier(),v==null?null:v.division(),v==null?null:v.lp(),state.fetchedAt()==null?null:state.fetchedAt().toString(),v!=null,state.stale(),state.error()));
                refreshing|=state.refreshing();
            }
            return new Result(matchId,queue,refreshing,List.copyOf(players));
        });
    }
    public record Result(String matchId,String queueType,boolean refreshing,List<PlayerRank> players) {}
    public record PlayerRank(int participantId,String status,String tier,String division,Integer leaguePoints,String fetchedAt,boolean cached,boolean stale,String error) {}
}
