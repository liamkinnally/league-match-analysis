package dev.leagueanalysis.analysis.leaderboard;

import dev.leagueanalysis.privacy.PrivacyRuntimeGuard;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class LeaderboardStore {
    private final tools.jackson.databind.ObjectMapper json=new tools.jackson.databind.ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PrivacyRuntimeGuard privacy;
    public LeaderboardStore(JdbcTemplate jdbc,PlatformTransactionManager manager,PrivacyRuntimeGuard privacy) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.privacy=privacy;
    }
    record Work(UUID token,int tier,String puuid,boolean icon) {}
    public Leaderboard read(Instant now,boolean enabled) {
        privacy.requireHealthy();
        return tx.execute(status->{
            // Serialize with publication so metadata and rows describe the same cycle.
            var state=jdbc.queryForMap("select fetched_at,ladder_due_at,next_tier,cycle_started_at,failed from league_analysis.leaderboard_state where singleton for share");
            var fetched=instant(state.get("fetched_at"));
            var rows=jdbc.query("""
                select e.*,m.game_name,m.tag_line,m.profile_icon_id from league_analysis.leaderboard_entry e
                left join league_analysis.leaderboard_member m on m.puuid=e.puuid
                where not e.pending and e.tier='CHALLENGER' and not league_analysis.privacy_blocked('puuid',e.puuid)
                  and not league_analysis.privacy_blocked('riot_id',lower(m.game_name)||'#'||lower(m.tag_line))
                order by case e.tier when 'CHALLENGER' then 0 when 'GRANDMASTER' then 1 else 2 end,
                    e.league_points desc,e.puuid limit 300
                """,(rs,n)->new Leaderboard.Entry(n+1,validName(rs)?rs.getString("game_name"):null,validName(rs)?rs.getString("tag_line"):null,
                    rs.getObject("profile_icon_id",Integer.class),rs.getString("tier"),rs.getInt("league_points"),rs.getInt("wins"),rs.getInt("losses")));
            return new Leaderboard("NA1","RANKED_SOLO_5x5",fetched!=null?"ready":enabled&&!(Boolean)state.get("failed")?"pending":"unavailable",
                    fetched,enabled?((Integer)state.get("next_tier")>0&&state.get("cycle_started_at")!=null
                        ?instant(state.get("cycle_started_at")).plusSeconds(900):instant(state.get("ladder_due_at"))):null,
                    fetched!=null&&((Boolean)state.get("failed")||!now.isBefore(fetched.plusSeconds(900))),List.copyOf(rows),cutoffs(now,enabled));
        });
    }
    /** Reserve the next provider slot before network I/O; a restarted worker cannot catch up in a burst. */
    public Optional<Work> claim(Instant now,Duration lease) {
        privacy.requireHealthy();
        return tx.execute(status->{
            var state=jdbc.queryForMap("select * from league_analysis.leaderboard_state where singleton for update");
            if(instant(state.get("request_due_at")).isAfter(now)||future(state.get("lease_until"),now))return Optional.empty();
            if(state.get("lease_until")!=null&&state.get("claimed_work_tier")!=null&&(Integer)state.get("claimed_work_tier")>=0) {
                // An interrupted bulk request may have reached Riot: do not repeat it in this cycle.
                abandonCutoff();
                if((Integer)state.get("claimed_work_tier")==0)jdbc.update("update league_analysis.leaderboard_state set failed=true where singleton");
                finishCycle(now);
                jdbc.update("update league_analysis.leaderboard_state set lease_id=null,lease_until=null,claimed_work_tier=null where singleton");
                return Optional.empty();
            }
            reuse(now);
            boolean initialFill=initialFill(now,true);
            int tier=(Integer)state.get("next_tier");
            if(tier>0&&(state.get("cycle_started_at")==null||!instant(state.get("cycle_started_at")).plusSeconds(900).isAfter(now))) {
                abandonCutoff();
                jdbc.update("update league_analysis.leaderboard_state set next_tier=0,cycle_started_at=null where singleton");
                tier=0;
            }
            String puuid=null;boolean icon=false;
            if(instant(state.get("ladder_due_at")).isAfter(now)||future(state.get("na_request_due_at"),now)) {
                var candidates=jdbc.query("""
                    select m.puuid, v.icon from league_analysis.leaderboard_member m
                    join league_analysis.leaderboard_entry e on e.puuid=m.puuid and not e.pending and e.tier='CHALLENGER'
                    cross join lateral (values (false,m.name_due_at),(true,m.icon_due_at)) v(icon,due)
                    where v.due<=? and (case when v.icon then ? else ? end)<=?
                    and not league_analysis.privacy_blocked('puuid',m.puuid)
                    and not league_analysis.privacy_blocked('riot_id',lower(m.game_name)||'#'||lower(m.tag_line))
                    order by v.due,case e.tier when 'CHALLENGER' then 0 when 'GRANDMASTER' then 1 else 2 end,
                        e.league_points desc,m.puuid,v.icon limit 1
                    """,(rs,n)->new Work(null,-1,rs.getString(1),rs.getBoolean(2)),time(now),
                        state.get("na_request_due_at"),state.get("americas_request_due_at"),time(now));
                if(candidates.isEmpty()) {
                    // Fast scheduler wakeups must not turn warm cache reuse into a one-second scan.
                    if(!initialFill)jdbc.update("update league_analysis.leaderboard_state set request_due_at=greatest(request_due_at,?) where singleton",time(now.plusSeconds(15)));
                    return Optional.empty();
                }
                puuid=candidates.getFirst().puuid();icon=candidates.getFirst().icon();tier=-1;
            }
            if(tier==0)jdbc.update("update league_analysis.leaderboard_state set cycle_started_at=? where singleton",time(now));
            var token=UUID.randomUUID();
            jdbc.update("update league_analysis.leaderboard_state set lease_id=?,lease_until=?,request_due_at=?,claimed_work_tier=? where singleton",token,time(now.plus(lease)),time(initialFill?now:now.plusSeconds(15)),tier);
            String hostDue=tier>=0||icon?"na_request_due_at":"americas_request_due_at";
            jdbc.update("update league_analysis.leaderboard_state set "+hostDue+"=greatest("+hostDue+",?) where singleton",time(now.plusSeconds(2)));
            return Optional.of(new Work(token,tier,puuid,icon));
        });
    }
    private boolean owns(Work work) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select lease_id=? from league_analysis.leaderboard_state where singleton for update",Boolean.class,work.token()));
    }
    public void ladder(Work work,List<LeaderboardClient.Standing> values,Instant now) {
        privacy.requireHealthy();
        tx.executeWithoutResult(status->{
            if(!owns(work))return;
            var histogram=CutoffCalculation.histogram(values); // Aggregate before privacy filtering.
            if(work.tier()==0) {
                jdbc.update("delete from league_analysis.leaderboard_entry");
                for(var row:values.stream().sorted(LeaderboardClient.ORDER).limit(300).toList()) {
                    if(!allowed(row.puuid()))continue;
                    jdbc.update("insert into league_analysis.leaderboard_entry(pending,puuid,tier,league_points,wins,losses) values(false,?,?,?,?,?)",
                            row.puuid(),row.tier(),row.lp(),row.wins(),row.losses());
                }
                jdbc.update("""
                    insert into league_analysis.leaderboard_member(puuid,name_due_at,icon_due_at)
                    select puuid,?,? from league_analysis.leaderboard_entry where not pending on conflict do nothing
                    """,time(now),time(now));
                jdbc.update("delete from league_analysis.leaderboard_member m where not exists(select 1 from league_analysis.leaderboard_entry e where e.puuid=m.puuid)");
                jdbc.update("update league_analysis.leaderboard_state set fetched_at=?,next_tier=1,failed=false,initial_fill_ready=true where singleton",time(now));
                jdbc.update("update league_analysis.leaderboard_cutoff set cycle_at=?,challenger_histogram=cast(? as jsonb),grandmaster_histogram=null where singleton",time(now),encode(histogram));
                reuse(now);
            } else {
                var aggregate=jdbc.queryForMap("select * from league_analysis.leaderboard_cutoff where singleton");
                var began=instant(aggregate.get("cycle_at"));
                if(began==null||!began.plusSeconds(900).isAfter(now)||aggregate.get("challenger_histogram")==null
                        ||work.tier()==2&&aggregate.get("grandmaster_histogram")==null) {
                    abandonCutoff();finishCycle(now);
                } else if(work.tier()==1) {
                    jdbc.update("update league_analysis.leaderboard_cutoff set grandmaster_histogram=cast(? as jsonb) where singleton",encode(histogram));
                    jdbc.update("update league_analysis.leaderboard_state set next_tier=2 where singleton");
                } else {
                    try {
                        var result=CutoffCalculation.calculate(decode(aggregate.get("challenger_histogram")),decode(aggregate.get("grandmaster_histogram")),histogram);
                        jdbc.update("""
                            update league_analysis.leaderboard_cutoff set fetched_at=?,failed=false,
                                challenger_boundary=?,challenger_promotion=?,grandmaster_boundary=?,grandmaster_promotion=?,
                                cycle_at=null,challenger_histogram=null,grandmaster_histogram=null where singleton
                            """,time(began),result.challenger().boundaryLp(),result.challenger().promotionLp(),result.grandmaster().boundaryLp(),result.grandmaster().promotionLp());
                    } catch(IllegalArgumentException|ArithmeticException invalid){abandonCutoff();}
                    finishCycle(now);
                }
            }
            release(work,now);
        });
    }
    private String encode(List<CutoffCalculation.Bucket> values) {return json.writeValueAsString(values);}
    private List<CutoffCalculation.Bucket> decode(Object encoded) {
        var result=new ArrayList<CutoffCalculation.Bucket>();
        for(var node:json.readTree(encoded.toString()))result.add(new CutoffCalculation.Bucket(node.path("lp").intValue(),node.path("count").intValue()));
        return result;
    }
    private void abandonCutoff(){jdbc.update("update league_analysis.leaderboard_cutoff set failed=true,cycle_at=null,challenger_histogram=null,grandmaster_histogram=null where singleton");}
    private void finishCycle(Instant now) {
        jdbc.update("update league_analysis.leaderboard_state set next_tier=0,ladder_due_at=greatest(coalesce(cycle_started_at,?)+interval '15 minutes',?) where singleton",time(now),time(now));
    }
    private Leaderboard.Cutoffs cutoffs(Instant now,boolean enabled) {
        return jdbc.query("select * from league_analysis.leaderboard_cutoff where singleton",(rs,n)->{
            var fetched=rs.getTimestamp("fetched_at");
            if(fetched==null)return new Leaderboard.Cutoffs(enabled&&!rs.getBoolean("failed")?"pending":"unavailable",null,false,null,null);
            return new Leaderboard.Cutoffs("ready",fetched.toInstant(),rs.getBoolean("failed")||!now.isBefore(fetched.toInstant().plusSeconds(900)),
                new Leaderboard.Cutoff(300,800,rs.getObject("challenger_boundary",Integer.class),rs.getInt("challenger_promotion")),
                new Leaderboard.Cutoff(700,400,rs.getObject("grandmaster_boundary",Integer.class),rs.getInt("grandmaster_promotion")));
        }).getFirst();
    }
    public void member(Work work,LeaderboardClient.Name name,Integer icon,Instant now) {
        privacy.requireHealthy();
        tx.executeWithoutResult(status->{
            if(!owns(work))return;
            if(allowed(work.puuid())&&(name==null||!blockedName(name))) {
                if(work.icon())jdbc.update("update league_analysis.leaderboard_member set profile_icon_id=?,icon_fetched_at=?,icon_due_at=? where puuid=?",icon,time(now),time(now.plusSeconds(86400)),work.puuid());
                else jdbc.update("update league_analysis.leaderboard_member set game_name=?,tag_line=?,name_fetched_at=?,name_due_at=? where puuid=?",name.gameName(),name.tagLine(),time(now),time(now.plusSeconds(86400)),work.puuid());
            } else {
                jdbc.update("delete from league_analysis.leaderboard_entry where puuid=?",work.puuid());
                jdbc.update("delete from league_analysis.leaderboard_member where puuid=?",work.puuid());
            }
            release(work,now);
        });
    }
    public void failure(Work work,Instant retry,boolean global,Instant now) {
        privacy.requireHealthy();
        tx.executeWithoutResult(status->{
            if(!owns(work))return;
            if(work.tier()>=0) {
                abandonCutoff();
                if(work.tier()==0)jdbc.update("update league_analysis.leaderboard_state set failed=true where singleton");
                finishCycle(now);
                jdbc.update("update league_analysis.leaderboard_state set ladder_due_at=greatest(ladder_due_at,?) where singleton",time(retry));
            } else jdbc.update("update league_analysis.leaderboard_member set "+(work.icon()?"icon_due_at":"name_due_at")+"=? where puuid=?",time(retry),work.puuid());
            if(global)jdbc.update("update league_analysis.leaderboard_state set request_due_at=greatest(request_due_at,?),ladder_due_at=greatest(ladder_due_at,?) where singleton",time(retry),time(retry));
            release(work,now);
        });
    }
    private void release(Work work,Instant now) {
        // Provider latency counts toward both durable host floors; global cooldowns always win.
        String hostDue=work.tier()>=0||work.icon()?"na_request_due_at":"americas_request_due_at";
        boolean initialFill=initialFill(now,false);
        jdbc.update("update league_analysis.leaderboard_state set lease_id=null,lease_until=null,claimed_work_tier=null,"
                +hostDue+"=greatest("+hostDue+",?),request_due_at=greatest(request_due_at,?) where singleton",
                time(now.plusSeconds(2)),time(initialFill?now:now.plusSeconds(15)));
    }
    /** Called under the singleton row lock. Completion is permanent, even after membership changes. */
    private boolean initialFill(Instant now,boolean activate) {
        var state=jdbc.queryForMap("select initial_fill_ready,fetched_at,initial_fill_started_at,initial_fill_deadline,initial_fill_completed_at from league_analysis.leaderboard_state where singleton");
        if(state.get("initial_fill_completed_at")!=null||state.get("fetched_at")==null||!Boolean.TRUE.equals(state.get("initial_fill_ready")))return false;
        var deadline=instant(state.get("initial_fill_deadline"));
        if(state.get("initial_fill_started_at")==null) {
            if(!activate)return false;
            deadline=now.plusSeconds(900);
            jdbc.update("update league_analysis.leaderboard_state set initial_fill_started_at=?,initial_fill_deadline=? where singleton",time(now),time(deadline));
        }
        boolean missing=Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from league_analysis.leaderboard_entry e
                left join league_analysis.leaderboard_member m on m.puuid=e.puuid
                where not e.pending and e.tier='CHALLENGER' and not league_analysis.privacy_blocked('puuid',e.puuid)
                and not league_analysis.privacy_blocked('riot_id',lower(m.game_name)||'#'||lower(m.tag_line))
                and (m.game_name is null or m.tag_line is null or m.profile_icon_id is null))
            """,Boolean.class));
        if(!missing||!deadline.isAfter(now)) {
            jdbc.update("update league_analysis.leaderboard_state set initial_fill_completed_at=? where singleton",time(now));
            return false;
        }
        return true;
    }
    private boolean allowed(String puuid) {return !Boolean.TRUE.equals(jdbc.queryForObject("select league_analysis.privacy_blocked('puuid',?) or exists(select 1 from league_analysis.leaderboard_member where puuid=? and league_analysis.privacy_blocked('riot_id',lower(game_name)||'#'||lower(tag_line)))",Boolean.class,puuid,puuid));}
    private boolean blockedName(LeaderboardClient.Name name){return Boolean.TRUE.equals(jdbc.queryForObject("select league_analysis.privacy_blocked('riot_id',lower(?)||'#'||lower(?))",Boolean.class,name.gameName(),name.tagLine()));}
    private void reuse(Instant now) {
        // Account capture timestamps, never a match ingestion's last_observed_at, establish name freshness.
        record CachedName(String puuid,String gameName,String tagLine,Instant fetchedAt) {}
        var names=jdbc.query("""
            select a.puuid,a.game_name,a.tag_line,a.captured_at
            from league_analysis.leaderboard_member m join (select distinct on (p.payload_json->>'puuid') p.payload_json->>'puuid' puuid,
                p.payload_json->>'gameName' game_name,p.payload_json->>'tagLine' tag_line,c.captured_at
                from league_analysis.source_capture c join league_analysis.source_payload p on p.id=c.source_payload_id
                where c.source_kind='ACCOUNT' and c.captured_at>? and jsonb_typeof(p.payload_json->'gameName')='string'
                    and jsonb_typeof(p.payload_json->'tagLine')='string'
                order by p.payload_json->>'puuid',c.captured_at desc) a
            on m.puuid=a.puuid where (m.name_fetched_at is null or m.name_fetched_at<a.captured_at)
                and not league_analysis.privacy_blocked('puuid',m.puuid)
                and not league_analysis.privacy_blocked('riot_id',lower(a.game_name)||'#'||lower(a.tag_line))
            """,(rs,n)->new CachedName(rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant()),time(now.minusSeconds(86400)));
        for(var name:names) {
            // Reused observations obey the same identity contract as new provider responses.
            if(!LeaderboardClient.validName(name.gameName(),name.tagLine()))continue;
            jdbc.update("""
                update league_analysis.leaderboard_member set game_name=?,tag_line=?,name_fetched_at=?,name_due_at=?
                where puuid=? and (name_fetched_at is null or name_fetched_at<?)
                """,name.gameName(),name.tagLine(),time(name.fetchedAt()),time(name.fetchedAt().plusSeconds(86400)),name.puuid(),time(name.fetchedAt()));
        }
        jdbc.update("""
            update league_analysis.leaderboard_member m set profile_icon_id=p.profile_icon_id,
                icon_fetched_at=p.fetched_at,icon_due_at=p.fetched_at+interval '24 hours'
            from league_analysis.player_profile_current p where p.puuid=m.puuid and p.platform='NA1'
                and p.fetched_at>? and p.profile_icon_id is not null
                and (m.icon_fetched_at is null or m.icon_fetched_at<p.fetched_at)
                and not league_analysis.privacy_blocked('puuid',m.puuid)
            """,time(now.minusSeconds(86400)));
    }
    private static boolean validName(ResultSet rs) throws SQLException {return LeaderboardClient.validName(rs.getString("game_name"),rs.getString("tag_line"));}
    private static boolean future(Object value,Instant now){return value!=null&&instant(value).isAfter(now);}
    private static Instant instant(Object value){return value==null?null:value instanceof Timestamp t?t.toInstant():((OffsetDateTime)value).toInstant();}
    private static Timestamp time(Instant value){return Timestamp.from(value);}
}
