package dev.leagueanalysis.analysis.profile;

import dev.leagueanalysis.analysis.rank.CurrentRankProvider;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.SummonerProfileClient;
import dev.leagueanalysis.ingestion.riot.application.*;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PlayerProfileService {
    private final JdbcPlayerProfileStore store;
    private final CurrentRankProvider ranks;
    private final SummonerProfileClient summoner;
    private final Clock clock;
    public PlayerProfileService(JdbcPlayerProfileStore store,CurrentRankProvider ranks,SummonerProfileClient summoner,Clock clock) {
        this.store=store;this.ranks=ranks;this.summoner=summoner;this.clock=clock;
    }
    public PlayerProfile load(UUID run,String cursor) {
        var subject=store.subject(run).orElseThrow(()->new PublicLookupException(404,"Verified profile not available yet.",null));
        var now=clock.instant();
        boolean pending=store.profilePending(subject,run,now);
        var solo=rank(subject,"RANKED_SOLO_5x5",pending);
        var flex=rank(subject,"RANKED_FLEX_SR",pending);
        var profile=store.summoner(subject,now);
        if(pending&&(profile.fetchedAt()==null||profile.stale()))
            profile=new PlayerProfile.Summoner(profile.fetchedAt()==null?"loading":profile.status(),profile.profileIconId(),profile.summonerLevel(),profile.revisionAt(),profile.fetchedAt(),true,profile.stale(),profile.retryNotBefore(),profile.error());
        return new PlayerProfile(new PlayerProfile.Identity(subject.gameName(),subject.tagLine()),profile,solo,flex,store.recent(subject,run,now),store.history(subject,cursor));
    }
    private PlayerProfile.Rank rank(JdbcPlayerProfileStore.Subject subject,String queue,boolean pending) {
        var state=ranks.peek(subject.platform(),subject.puuid(),queue);var v=state.value();
        Integer wins=v==null?null:v.wins(),losses=v==null?null:v.losses();
        Double rate=wins==null||losses==null||(long)wins+losses==0?null:Math.round(wins*1000d/((long)wins+losses))/10d;
        boolean rankPending=state.refreshing()||(pending&&(v==null||state.stale()));
        return new PlayerProfile.Rank(v==null?rankPending?"loading":"unavailable":v.status(),v==null?null:v.tier(),v==null?null:v.division(),v==null?null:v.lp(),wins,losses,rate,"unknown",state.fetchedAt(),rankPending,state.stale(),state.retryNotBefore(),state.error());
    }
    /** Persist the queued stages once Account verification makes their subject available. */
    public boolean markPending(UUID run,Instant deadline) {
        var subject=store.subject(run).orElse(null);
        if(subject==null)return false;
        store.markProfilePending(subject,run,deadline);return true;
    }
    public void finishWork(UUID run){store.finishProfileWork(run);}
    /** Internal identity key only; never serialized into a public projection. */
    public String identityKey(UUID run) {return subject(run).platform()+":"+subject(run).puuid();}
    public void bindRecent(UUID run,UUID recentRun){store.bindRecent(subject(run),recentRun);}
    public void claimRecent(UUID run) {store.claimRecent(subject(run),clock.instant());}
    private JdbcPlayerProfileStore.Subject subject(UUID run){return store.subject(run).orElseThrow(()->new PublicLookupException(404,"Verified profile not available.",null));}
    /** Called only as a continuation of an already admitted lookup, never from a projection read. */
    public PublicIngestionWork refreshWork(UUID run) {
        return new PublicIngestionWork(){
            private int stage;
            public boolean step(){
                var subject=store.subject(run).orElse(null);if(subject==null)return true;
                if(stage==0){
                    var refreshId=UUID.randomUUID();
                    if(store.claimSummoner(subject.platform(),subject.puuid(),clock.instant(),refreshId)) {
                        try{var result=summoner.fetch(subject.platform(),subject.puuid());store.summonerSuccess(subject,result.iconId(),result.level(),result.revisionAt(),clock.instant(),refreshId);}
                        catch(RiotGatewayException failure){var retry=failure.retryNotBefore()==null?clock.instant().plusSeconds(60):failure.retryNotBefore();store.summonerFailure(subject,failure.code()==RiotFailureCode.RATE_LIMITED?"RATE_LIMITED":"UPSTREAM_UNAVAILABLE",retry,refreshId);if(failure.code()==RiotFailureCode.RATE_LIMITED)throw failure;}
                    }
                    stage++;
                    return false;
                }
                ranks.refresh(subject.platform(),subject.puuid(),"RANKED_SOLO_5x5");return true;
            }
        };
    }
}
