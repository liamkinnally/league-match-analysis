package dev.leagueanalysis.analysis.profile;

import dev.leagueanalysis.analysis.rank.RankSnapshotStore;
import dev.leagueanalysis.privacy.PrivacyRuntimeGuard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcPlayerProfileStore implements RankSnapshotStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PrivacyRuntimeGuard privacy;
    private final TransactionTemplate transactions;
    public JdbcPlayerProfileStore(JdbcTemplate jdbc, ObjectMapper json, PrivacyRuntimeGuard privacy,
            org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.json=json; this.privacy=privacy; this.transactions=new TransactionTemplate(manager);
    }
    public record Subject(String puuid,String platform,String gameName,String tagLine) {}
    @Override public void requireHealthy() { privacy.requireHealthy(); }
    public Optional<Subject> subject(UUID run) {
        requireHealthy();
        return jdbc.query("""
            select i.puuid,r.platform_route,r.requested_game_name,r.requested_tag_line
            from league_analysis.ingestion_run r join league_analysis.riot_identity i on i.puuid=r.resolved_puuid
            where r.id=? and r.public_request and r.lookup_kind='HISTORY'
                and nullif(r.requested_game_name,'') is not null and nullif(r.requested_tag_line,'') is not null
                and not league_analysis.privacy_blocked('puuid',i.puuid)
                and exists(select 1 from league_analysis.source_capture c
                    join league_analysis.source_payload p on p.id=c.source_payload_id
                    where c.ingestion_run_id=r.id and c.source_kind='ACCOUNT'
                      and p.payload_json->>'puuid'=i.puuid and not league_analysis.privacy_json_excluded(p.payload_json))
            """, (rs,n)->new Subject(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),run).stream().findFirst();
    }
    public void markProfilePending(Subject subject,UUID run,Instant deadline) {
        if(!isAllowed(subject.puuid()))return;
        jdbc.update("""
            insert into league_analysis.player_profile_current(puuid,platform,profile_pending_run_id,profile_pending_until)
            values (?,?,?,?) on conflict(puuid,platform) do update
            set profile_pending_run_id=excluded.profile_pending_run_id,profile_pending_until=excluded.profile_pending_until
            where league_analysis.player_profile_current.profile_pending_until is null
               or league_analysis.player_profile_current.profile_pending_until<=excluded.profile_pending_until
            """,subject.puuid(),subject.platform(),run,time(deadline));
    }
    public void finishProfileWork(UUID run) {
        requireHealthy();
        // Retain the deadline to fence older work and distinguish completion from work not yet queued.
        jdbc.update("""
            update league_analysis.player_profile_current set profile_pending_run_id=null
            where profile_pending_run_id=? and not league_analysis.privacy_blocked('puuid',puuid)
            """,run);
    }
    public boolean profilePending(Subject subject,UUID run,Instant now) {
        if(!isAllowed(subject.puuid()))return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from league_analysis.player_profile_current where puuid=? and platform=?
                and profile_pending_run_id is not null and profile_pending_until>?)
            or exists(select 1 from league_analysis.ingestion_run r where r.id=? and r.resolved_puuid=?
                and r.public_request and r.lookup_kind='HISTORY' and r.status='RUNNING' and r.started_at>?
                and not exists(select 1 from league_analysis.player_profile_current p
                    where p.puuid=r.resolved_puuid and p.platform=r.platform_route
                      and p.profile_pending_until>=r.started_at+interval '900 seconds'))
            """,Boolean.class,subject.puuid(),subject.platform(),time(now),run,subject.puuid(),time(now.minusSeconds(900))));
    }
    public void claimRecent(Subject subject,Instant now) {
        if(!isAllowed(subject.puuid()))throw new dev.leagueanalysis.ingestion.riot.application.PublicLookupException(404,"Profile not available.",null);
        transactions.executeWithoutResult(status->{
            jdbc.update("insert into league_analysis.player_profile_current(puuid,platform) values (?,?) on conflict do nothing",subject.puuid(),subject.platform());
            var previous=jdbc.queryForObject("select recent_requested_at from league_analysis.player_profile_current where puuid=? and platform=? for update",OffsetDateTime.class,subject.puuid(),subject.platform());
            if(previous!=null&&previous.toInstant().plusSeconds(900).isAfter(now))throw new dev.leagueanalysis.ingestion.riot.application.PublicLookupException(429,"Recent record was requested recently.",previous.toInstant().plusSeconds(900));
            jdbc.update("update league_analysis.player_profile_current set recent_requested_at=? where puuid=? and platform=?",time(now),subject.puuid(),subject.platform());
        });
    }
    public void bindRecent(Subject subject,UUID recentRun) {
        if(!isAllowed(subject.puuid()))return;
        jdbc.update("update league_analysis.player_profile_current set recent_run_id=? where puuid=? and platform=?",recentRun,subject.puuid(),subject.platform());
    }
    @Override public boolean isAllowed(String puuid) {
        requireHealthy();
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from league_analysis.riot_identity where puuid=? and not league_analysis.privacy_blocked('puuid',puuid))",Boolean.class,puuid));
    }
    @Override public Optional<Snapshot> read(String platform,String puuid) {
        if(!isAllowed(puuid)) return Optional.empty();
        return jdbc.query("select entries::text,fetched_at,retry_at,error,lease_until from league_analysis.rank_refresh_state where puuid=? and platform=?",
                (rs,n)->new Snapshot(values(rs.getString(1)),instant(rs,2),instant(rs,3),rs.getString(4),instant(rs,5)),puuid,platform).stream().findFirst();
    }
    private Map<String,Value> values(String encoded) {
        if(encoded==null) return null;
        var result=new LinkedHashMap<String,Value>();
        var root=json.readTree(encoded);
        for(var key:List.of("RANKED_SOLO_5x5","RANKED_FLEX_SR")) {
            var node=root.get(key); if(node==null) continue;
            result.put(key,new Value(node.path("status").asText(),text(node,"tier"),text(node,"division"),number(node,"lp"),number(node,"wins"),number(node,"losses")));
        }
        return Map.copyOf(result);
    }
    private String text(tools.jackson.databind.JsonNode node,String key) { return node.path(key).isString()?node.path(key).stringValue():null; }
    private Integer number(tools.jackson.databind.JsonNode node,String key) { return node.path(key).isIntegralNumber()?node.path(key).intValue():null; }
    @Override public boolean claim(String platform,String puuid,Instant now,UUID refreshId) { return claim("rank_refresh_state",platform,puuid,now,Duration.ofMinutes(5),refreshId); }
    public boolean claimSummoner(String platform,String puuid,Instant now,UUID refreshId) { return claim("player_profile_current",platform,puuid,now,Duration.ofHours(24),refreshId); }
    private boolean claim(String table,String platform,String puuid,Instant now,Duration ttl,UUID refreshId) {
        if(!isAllowed(puuid)) return false;
        return Boolean.TRUE.equals(transactions.execute(status->{
            jdbc.update("insert into league_analysis."+table+"(puuid,platform) values (?,?) on conflict do nothing",puuid,platform);
            return jdbc.update("update league_analysis."+table+" set lease_until=?,lease_id=? where puuid=? and platform=? and (lease_until is null or lease_until<=?) and (retry_at is null or retry_at<=?) and (fetched_at is null or fetched_at<=?)",
                time(now.plusSeconds(120)),refreshId,puuid,platform,time(now),time(now),time(now.minus(ttl)))==1;
        }));
    }
    @Override public void success(String platform,String puuid,Map<String,Value> values,Instant now,UUID refreshId) {
        if(!isAllowed(puuid)) return;
        transactions.executeWithoutResult(status->{
            jdbc.queryForObject("select puuid from league_analysis.rank_refresh_state where puuid=? and platform=? for update",String.class,puuid,platform);
            var lease=jdbc.queryForObject("select lease_id from league_analysis.rank_refresh_state where puuid=? and platform=?",UUID.class,puuid,platform);
            if(!refreshId.equals(lease))return;
            var last=jdbc.queryForObject("select fetched_at from league_analysis.rank_refresh_state where puuid=? and platform=?",OffsetDateTime.class,puuid,platform);
            if(last!=null&&!last.toInstant().isBefore(now)) return;
            jdbc.update("update league_analysis.rank_refresh_state set entries=cast(? as jsonb),fetched_at=?,retry_at=null,error=null,lease_until=null,refresh_id=? where puuid=? and platform=?",
                    json.writeValueAsString(values),time(now),refreshId,puuid,platform);
            for(var queue:List.of("RANKED_SOLO_5x5","RANKED_FLEX_SR")) {
                var v=values.getOrDefault(queue,new Value("unranked",null,null,null,null,null));
                jdbc.update("""
                    insert into league_analysis.rank_observation(id,puuid,platform,queue_type,observed_at,status,tier,division,league_points,wins,losses,refresh_id)
                    select ?,?,?,?,?,?,?,?,?,?,?,? where not exists(select 1 from league_analysis.rank_observation
                        where puuid=? and platform=? and queue_type=? and observed_at>?)
                    on conflict(puuid,platform,queue_type,refresh_id) do nothing
                    """,UUID.randomUUID(),puuid,platform,queue,time(now),v.status(),v.tier(),v.division(),v.lp(),v.wins(),v.losses(),refreshId,
                    puuid,platform,queue,time(now.minusSeconds(900)));
            }
        });
    }
    @Override public void failure(String platform,String puuid,String error,Instant retry,UUID refreshId) {
        if(!isAllowed(puuid))return;
        jdbc.update("update league_analysis.rank_refresh_state set error=?,retry_at=?,lease_until=null where puuid=? and platform=? and lease_id=?",error,time(retry),puuid,platform,refreshId);
    }
    public void summonerSuccess(Subject subject,Integer icon,Long level,Instant revision,Instant now,UUID refreshId) {
        if(!isAllowed(subject.puuid()))return;
        jdbc.update("update league_analysis.player_profile_current set profile_icon_id=?,summoner_level=?,revision_at=?,fetched_at=?,retry_at=null,error=null,lease_until=null where puuid=? and platform=? and lease_id=? and (fetched_at is null or fetched_at<?)",
            icon,level,time(revision),time(now),subject.puuid(),subject.platform(),refreshId,time(now));
    }
    public void summonerFailure(Subject subject,String error,Instant retry,UUID refreshId) {
        if(!isAllowed(subject.puuid()))return;
        jdbc.update("update league_analysis.player_profile_current set error=?,retry_at=?,lease_until=null where puuid=? and platform=? and lease_id=?",error,time(retry),subject.puuid(),subject.platform(),refreshId);
    }
    public PlayerProfile.Summoner summoner(Subject subject,Instant now) {
        if(!isAllowed(subject.puuid()))throw new IllegalStateException("PROFILE_UNAVAILABLE");
        return jdbc.query("select profile_icon_id,summoner_level,revision_at,fetched_at,retry_at,error,lease_until from league_analysis.player_profile_current where puuid=? and platform=?",(rs,n)->{
            var fetched=instant(rs,4);var lease=instant(rs,7);boolean pending=lease!=null&&lease.isAfter(now);
            return new PlayerProfile.Summoner(fetched!=null?"available":pending?"loading":"unavailable",rs.getObject(1,Integer.class),rs.getObject(2,Long.class),instant(rs,3),fetched,pending,
                fetched!=null&&fetched.isBefore(now.minus(Duration.ofHours(24))),instant(rs,5),rs.getString(6));
        },subject.puuid(),subject.platform()).stream().findFirst().orElse(new PlayerProfile.Summoner("unavailable",null,null,null,null,false,false,null,null));
    }
    public PlayerProfile.RankHistory history(Subject subject,String cursor) {
        if(!isAllowed(subject.puuid()))throw new IllegalStateException("PROFILE_UNAVAILABLE");
        Instant before=Instant.parse("9999-01-01T00:00:00Z");UUID beforeId=new UUID(-1L,-1L);
        if(cursor!=null&&!cursor.isBlank()) {
            try { var parts=new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1); if(parts.length!=2)throw new IllegalArgumentException(); before=Instant.parse(parts[0]); beforeId=UUID.fromString(parts[1]); }
            catch(RuntimeException failure){throw new IllegalArgumentException("INVALID_HISTORY_CURSOR");}
        }
        var observations=jdbc.query("""
            select id,observed_at,status,tier,division,league_points,wins,losses,period_key
            from league_analysis.rank_observation where puuid=? and platform=? and queue_type='RANKED_SOLO_5x5'
              and (observed_at,id)<(?,?) order by observed_at desc,id desc limit 51
            """,(rs,n)->new PlayerProfile.Observation(rs.getObject(1,UUID.class),instant(rs,2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,Integer.class),rs.getObject(7,Integer.class),rs.getObject(8,Integer.class),rs.getString(9)),subject.puuid(),subject.platform(),time(before),beforeId);
        var since=jdbc.queryForObject("select min(observed_at) from league_analysis.rank_observation where puuid=? and platform=? and queue_type='RANKED_SOLO_5x5'",OffsetDateTime.class,subject.puuid(),subject.platform());
        String next=null;
        if(observations.size()>50){observations=new ArrayList<>(observations.subList(0,50));var tail=observations.getLast();next=Base64.getUrlEncoder().withoutPadding().encodeToString((tail.observedAt()+"|"+tail.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        return new PlayerProfile.RankHistory(since==null?null:since.toInstant(),List.copyOf(observations),next);
    }
    public PlayerProfile.RecentRecord recent(Subject subject,UUID run,Instant now) {
        if(!isAllowed(subject.puuid()))throw new IllegalStateException("PROFILE_UNAVAILABLE");
        var pages=jdbc.query("""
            select id,status,page_end_time,(select count(*)::integer from league_analysis.ingestion_item item where item.ingestion_run_id=ingestion_run.id) from league_analysis.ingestion_run
            where (resolved_puuid=? or id=(select recent_run_id from league_analysis.player_profile_current where puuid=? and platform=?)) and public_request and lookup_kind='HISTORY' and queue_id=420 and page_start=0
            order by started_at desc,id desc limit 1
            """,(rs,n)->new RecentPage(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,Long.class),rs.getObject(4,Integer.class)),subject.puuid(),subject.puuid(),subject.platform());
        var page=pages.isEmpty()?null:pages.getFirst();
        Long fallbackEnd=jdbc.queryForObject("select page_end_time from league_analysis.ingestion_run where id=?",Long.class,run);
        var snapshot=page!=null&&page.end()!=null?Instant.ofEpochSecond(page.end()):fallbackEnd==null?now:Instant.ofEpochSecond(fallbackEnd);
        var lastRefresh=jdbc.queryForObject("select max(started_at) from league_analysis.ingestion_run where resolved_puuid=? and public_request and lookup_kind='HISTORY' and page_start=0 and status in ('RUNNING','COMPLETE')",OffsetDateTime.class,subject.puuid());
        var retry=lastRefresh==null?null:lastRefresh.toInstant().plusSeconds(900);
        var recentRequest=jdbc.query("select recent_requested_at from league_analysis.player_profile_current where puuid=? and platform=?",(rs,n)->Optional.ofNullable(instant(rs,1)),subject.puuid(),subject.platform()).stream().findFirst().orElse(Optional.empty()).orElse(null);
        if(recentRequest!=null&&(retry==null||recentRequest.plusSeconds(900).isAfter(retry)))retry=recentRequest.plusSeconds(900);
        if(retry!=null&&!retry.isAfter(now))retry=null;
        var rows=jdbc.query("""
            select p.win,m.game_creation_ms from league_analysis.riot_participant p join league_analysis.riot_match m on m.match_id=p.match_id
            where p.puuid=? and m.game_creation_ms<=? and m.queue_id=420 and m.map_id=11 and not league_analysis.privacy_blocked('match',m.match_id)
            order by m.game_creation_ms desc,m.match_id desc limit 20
            """,(rs,n)->new RecentGame(rs.getObject(1,Boolean.class),rs.getLong(2)),subject.puuid(),snapshot.toEpochMilli());
        int wins=(int)rows.stream().filter(r->Boolean.TRUE.equals(r.win())).count();int losses=(int)rows.stream().filter(r->Boolean.FALSE.equals(r.win())).count();
        boolean loading=page!=null&&"RUNNING".equals(page.status());
        int checked=page==null||page.count()==null?rows.size():page.count();
        return new PlayerProfile.RecentRecord(420,20,wins+losses,wins,losses,snapshot,rows.isEmpty()?null:Instant.ofEpochMilli(rows.getLast().started()),
            "solo-eligibility-unverified-v1",checked,Math.max(rows.size(),checked),0,loading?"loading":"unverified",!loading&&retry==null,retry);
    }
    private record RecentPage(UUID id,String status,Long end,Integer count) {}
    private record RecentGame(Boolean win,long started) {}
    private static OffsetDateTime time(Instant value){return value==null?null:value.atOffset(ZoneOffset.UTC);}
    private static Instant instant(ResultSet rs,int column)throws SQLException{var value=rs.getObject(column,OffsetDateTime.class);return value==null?null:value.toInstant();}
}
