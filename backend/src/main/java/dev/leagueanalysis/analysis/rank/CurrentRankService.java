package dev.leagueanalysis.analysis.rank;

import dev.leagueanalysis.ingestion.riot.adapter.out.riot.RiotHttpTransport;
import dev.leagueanalysis.ingestion.riot.config.RiotProperties;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class CurrentRankService {
    private static final long TTL = Duration.ofMinutes(5).toMillis();
    private static final long BACKOFF = Duration.ofSeconds(30).toMillis();
    private static final int MAX_CACHE = 1000;
    private final RankRosterQuery rosters;
    private final RiotProperties properties;
    private final RiotHttpTransport transport;
    private final ObjectMapper json;
    private final LongSupplier clock;
    private final Executor executor;
    private final Map<Key, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private final ArrayDeque<Long> requestTimes = new ArrayDeque<>();
    private long blockedUntil;
    private String blockedError;

    @Autowired
    public CurrentRankService(RankRosterQuery rosters, RiotProperties properties, RiotHttpTransport transport,
            ObjectMapper json, Clock clock) {
        this(rosters, properties, transport, json, clock::millis,
                new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
                        Thread.ofPlatform().daemon().name("current-rank-", 0).factory(), new ThreadPoolExecutor.AbortPolicy()));
    }

    CurrentRankService(RankRosterQuery rosters, RiotProperties properties, RiotHttpTransport transport,
            ObjectMapper json, LongSupplier clock, Executor executor) {
        this.rosters=rosters; this.properties=properties; this.transport=transport;
        this.json=json; this.clock=clock; this.executor=executor;
    }

    public Optional<Result> load(String matchId) {
        return rosters.load(matchId).map(roster -> {
            String queue = switch(roster.queueId()) { case 420 -> "RANKED_SOLO_5x5"; case 440 -> "RANKED_FLEX_SR"; default -> null; };
            var players = new ArrayList<PlayerRank>();
            boolean refreshing = false;
            for(var player : roster.players()) {
                var state = lookup(matchId, queue, player);
                players.add(state.player()); refreshing |= state.refreshing();
            }
            return new Result(matchId, queue, refreshing, List.copyOf(players));
        });
    }

    private synchronized State lookup(String matchId, String queue, RankRosterQuery.Player player) {
        String unavailable = queue == null ? "UNSUPPORTED_QUEUE"
                : !matchId.startsWith(properties.platformRoute() + "_") ? "UNSUPPORTED_PLATFORM"
                : properties.apiKey().isBlank() ? "MISSING_CREDENTIALS"
                : player.puuid() == null || player.puuid().isBlank() ? "MISSING_IDENTITY" : null;
        if (unavailable != null) return new State(unavailable(player.participantId(), unavailable), false);
        long now = clock.getAsLong();
        var key = new Key(properties.platformRoute(), player.puuid(), queue);
        var entry = cache.get(key);
        if (entry == null) {
            if (cache.size() >= MAX_CACHE) {
                var candidate = cache.entrySet().stream().filter(e -> !e.getValue().pending).findFirst();
                if (candidate.isEmpty()) return new State(unavailable(player.participantId(), "BUSY"), false);
                cache.remove(candidate.orElseThrow().getKey());
            }
            entry = new Entry(); cache.put(key, entry);
        }
        if (!entry.pending && now >= entry.retryAt && (entry.value == null || now-entry.fetchedAt >= TTL)) {
            if (now < blockedUntil) {
                entry.error = blockedError; entry.retryAt=blockedUntil;
            } else {
                entry.pending=true;
                try { executor.execute(() -> fetch(key)); }
                catch (RejectedExecutionException ignored) { entry.pending=false;entry.error="BUSY";entry.retryAt=now+BACKOFF; }
            }
        }
        var value=entry.value;
        boolean stale=value != null && now-entry.fetchedAt >= TTL;
        return new State(new PlayerRank(player.participantId(), value == null ? entry.pending ? "loading" : "unavailable" : value.status(),
                value == null ? null : value.tier(), value == null ? null : value.division(), value == null ? null : value.lp(),
                value == null ? null : Instant.ofEpochMilli(entry.fetchedAt).toString(), value != null, stale, entry.error), entry.pending);
    }

    private void fetch(Key key) {
        synchronized(this) {
            long now=clock.getAsLong();
            while(!requestTimes.isEmpty() && requestTimes.getFirst() <= now-120_000) requestTimes.removeFirst();
            // Conservative local budget leaves capacity for ingestion, which shares the credential.
            if (now < blockedUntil) {
                fail(key, blockedError, blockedUntil);
                return;
            }
            if (requestTimes.size() >= 80) {
                fail(key, "RATE_LIMITED", requestTimes.getFirst()+120_000);
                return;
            }
            if (requestTimes.stream().filter(time -> time > now-1000).count() >= 10) {
                fail(key, "RATE_LIMITED", now+BACKOFF);
                return;
            }
            requestTimes.addLast(now);
        }
        try {
            var request = HttpRequest.newBuilder(URI.create("https://"+key.platform().toLowerCase(Locale.ROOT)
                    +".api.riotgames.com/lol/league/v4/entries/by-puuid/"+ URLEncoder.encode(key.puuid(), StandardCharsets.UTF_8)))
                    .header("X-Riot-Token",properties.apiKey()).timeout(properties.requestTimeout()).GET().build();
            var response=transport.send(request,properties.requestTimeout(),Math.min(properties.maxResponseBytes(),262144));
            if(response.statusCode()!=200) {
                long retry=clock.getAsLong()+BACKOFF;
                String error="UPSTREAM_UNAVAILABLE";
                if(response.statusCode()==429) { error="RATE_LIMITED";retry=retryAfter(response); }
                if(response.statusCode()==401 || response.statusCode()==403) { error="AUTH_UNAVAILABLE";retry=clock.getAsLong()+60_000; }
                synchronized(this) {
                    if ((response.statusCode()==429 || response.statusCode()==401 || response.statusCode()==403)
                            && retry >= blockedUntil) {
                        blockedUntil = retry;
                        blockedError = error;
                    }
                    fail(key,error,retry);
                }
                return;
            }
            var body=json.readTree(response.body());
            if(!body.isArray()) throw new IllegalArgumentException();
            Value value=new Value("unranked",null,null,null);
            boolean found=false;
            for(var row:body) {
                if(!row.isObject() || !row.path("queueType").isString()) throw new IllegalArgumentException();
                String tier=row.path("tier").asText(""); String division=row.path("rank").asText("");
                if(!Set.of("IRON","BRONZE","SILVER","GOLD","PLATINUM","EMERALD","DIAMOND","MASTER","GRANDMASTER","CHALLENGER").contains(tier)
                        || !Set.of("I","II","III","IV").contains(division) || !row.path("leaguePoints").isIntegralNumber()
                        || !row.path("leaguePoints").canConvertToInt() || row.path("leaguePoints").intValue()<0) throw new IllegalArgumentException();
                if(!key.queue().equals(row.path("queueType").stringValue())) continue;
                if(found) throw new IllegalArgumentException();
                found=true;value=new Value("ranked",tier,division,row.path("leaguePoints").intValue());
            }
            synchronized(this) {
                var entry=cache.get(key);entry.value=value;entry.fetchedAt=clock.getAsLong();entry.retryAt=0;entry.error=null;entry.pending=false;
            }
        } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); synchronized(this) { fail(key,"UPSTREAM_UNAVAILABLE",clock.getAsLong()+BACKOFF); } }
        catch(Exception ignored) { synchronized(this) { fail(key,"UPSTREAM_UNAVAILABLE",clock.getAsLong()+BACKOFF); } }
    }

    private long retryAfter(RiotHttpTransport.Response response) {
        long now=clock.getAsLong();
        String value=response.headers().firstValue("Retry-After").orElse("30");
        try { return Math.addExact(now, Math.multiplyExact(Math.max(1,Long.parseLong(value)),1000)); }
        catch(RuntimeException ignored) {
            try { return Math.max(now+1000,ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()); }
            catch(RuntimeException invalid) { return now+BACKOFF; }
        }
    }
    private void fail(Key key,String error,long retry) { var entry=cache.get(key);entry.pending=false;entry.error=error;entry.retryAt=retry; }
    private PlayerRank unavailable(int id,String error) { return new PlayerRank(id,"unavailable",null,null,null,null,false,false,error); }
    @PreDestroy void close() { if(executor instanceof ExecutorService service) service.shutdownNow(); }
    public record Result(String matchId,String queueType,boolean refreshing,List<PlayerRank> players) {}
    public record PlayerRank(int participantId,String status,String tier,String division,Integer leaguePoints,String fetchedAt,boolean cached,boolean stale,String error) {}
    private record State(PlayerRank player,boolean refreshing) {}
    private record Key(String platform,String puuid,String queue) {}
    private record Value(String status,String tier,String division,Integer lp) {}
    private static final class Entry { Value value;long fetchedAt;long retryAt;boolean pending;String error; }
}
