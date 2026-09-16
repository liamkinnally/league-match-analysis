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
public final class CurrentRankProvider {
    private static final long TTL = Duration.ofMinutes(5).toMillis();
    private static final long BACKOFF = Duration.ofSeconds(30).toMillis();
    private static final int MAX_CACHE = 1000;
    private final RankSnapshotStore store;
    private final RiotProperties properties;
    private final RiotHttpTransport transport;
    private final ObjectMapper json;
    private final LongSupplier clock;
    private final Executor executor;
    private final Map<Key, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, PlatformBudget> budgets = new HashMap<>();
    private static final class PlatformBudget {
        final ArrayDeque<Long> requestTimes = new ArrayDeque<>();
        long blockedUntil;
        String blockedError;
    }

    @Autowired
    public CurrentRankProvider(RankSnapshotStore store, RiotProperties properties, RiotHttpTransport transport,
            ObjectMapper json, Clock clock) {
        this(store, properties, transport, json, clock::millis,
                new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
                        Thread.ofPlatform().daemon().name("current-rank-", 0).factory(), new ThreadPoolExecutor.AbortPolicy()));
    }

    CurrentRankProvider(RankSnapshotStore store, RiotProperties properties, RiotHttpTransport transport,
            ObjectMapper json, LongSupplier clock, Executor executor) {
        this.store=store; this.properties=properties; this.transport=transport;
        this.json=json; this.clock=clock; this.executor=executor;
    }

    public State refresh(String platform,String puuid,String queue) { return lookup(platform,puuid,queue,true); }
    public State peek(String platform,String puuid,String queue) { return lookup(platform,puuid,queue,false); }
    private synchronized State lookup(String platform,String puuid,String queue,boolean admit) {
        if(store!=null) store.requireHealthy();
        String unavailable = !dev.leagueanalysis.ingestion.riot.domain.RiotPlatform.supported(platform) ? "UNSUPPORTED_PLATFORM"
                : puuid==null || puuid.isBlank() ? "MISSING_IDENTITY" : null;
        if(unavailable!=null)return new State(null,null,false,false,null,unavailable);
        long now = clock.getAsLong();
        var budget = budgets.computeIfAbsent(platform, ignored -> new PlatformBudget());
        var key = new Key(platform, puuid);
        if(store!=null&&!store.isAllowed(puuid)){cache.remove(key);return new State(null,null,false,false,null,"ACCOUNT_UNAVAILABLE");}
        var entry = cache.get(key);
        if (entry == null) {
            if (cache.size() >= MAX_CACHE) {
                var candidate = cache.entrySet().stream().filter(e -> !e.getValue().pending).findFirst();
                if (candidate.isEmpty()) return new State(null,null,false,false,null,"BUSY");
                cache.remove(candidate.orElseThrow().getKey());
            }
            entry = new Entry();
            if(store!=null) {
                var saved=store.read(platform,puuid).orElse(null);
                if(saved!=null) {entry.values=saved.values();entry.fetchedAt=saved.fetchedAt()==null?0:saved.fetchedAt().toEpochMilli();entry.retryAt=saved.retryAt()==null?0:saved.retryAt().toEpochMilli();entry.error=saved.error();entry.leaseUntil=saved.leaseUntil()==null?0:saved.leaseUntil().toEpochMilli();}
            }
            cache.put(key, entry);
        }
        if(store!=null&&!entry.pending) {
            var saved=store.read(platform,puuid).orElse(null);
            if(saved==null) { entry.values=null;entry.fetchedAt=0;entry.leaseUntil=0; }
            else {entry.values=saved.values();entry.fetchedAt=saved.fetchedAt()==null?0:saved.fetchedAt().toEpochMilli();entry.retryAt=saved.retryAt()==null?0:saved.retryAt().toEpochMilli();entry.error=saved.error();entry.leaseUntil=saved.leaseUntil()==null?0:saved.leaseUntil().toEpochMilli();}
        }
        if(properties.apiKey().isBlank()) {entry.error="MISSING_CREDENTIALS";admit=false;}
        var refreshId=UUID.randomUUID();
        if (admit && !entry.pending && now >= entry.retryAt && (entry.values == null || now-entry.fetchedAt >= TTL)) {
            if (now < budget.blockedUntil) {
                entry.error = budget.blockedError; entry.retryAt=budget.blockedUntil;
            } else if(store==null || store.claim(platform,puuid,Instant.ofEpochMilli(now),refreshId)) {
                entry.pending=true;entry.refreshId=refreshId;
                try { executor.execute(() -> fetch(key,refreshId)); }
                catch (RejectedExecutionException ignored) { fail(key,"BUSY",now+BACKOFF); }
            }
        }
        var value=entry.values==null?null:entry.values.getOrDefault(queue,new RankSnapshotStore.Value("unranked",null,null,null,null,null));
        return new State(value,entry.values==null?null:Instant.ofEpochMilli(entry.fetchedAt),entry.pending||entry.leaseUntil>now,
                value!=null&&now-entry.fetchedAt>=TTL,entry.retryAt==0?null:Instant.ofEpochMilli(entry.retryAt),entry.error);
    }

    private void fetch(Key key,UUID refreshId) {
        synchronized(this) {
            long now=clock.getAsLong();
            var budget=budgets.computeIfAbsent(key.platform(), ignored -> new PlatformBudget());
            var requestTimes=budget.requestTimes;
            while(!requestTimes.isEmpty() && requestTimes.getFirst() <= now-120_000) requestTimes.removeFirst();
            // Conservative local budget leaves capacity for ingestion, which shares the credential.
            if (now < budget.blockedUntil) {
                fail(key, budget.blockedError, budget.blockedUntil);
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
                    var budget=budgets.computeIfAbsent(key.platform(), ignored -> new PlatformBudget());
                    if ((response.statusCode()==429 || response.statusCode()==401 || response.statusCode()==403)
                            && retry >= budget.blockedUntil) {
                        budget.blockedUntil = retry;
                        budget.blockedError = error;
                    }
                    fail(key,error,retry);
                }
                return;
            }
            var body=json.readTree(response.body());
            if(!body.isArray()) throw new IllegalArgumentException();
            var values=new LinkedHashMap<String,RankSnapshotStore.Value>();
            for(var row:body) {
                if(!row.isObject() || !row.path("queueType").isString()) throw new IllegalArgumentException();
                String queue=row.path("queueType").stringValue();
                // Other ladders can use different tier schemas; only these queues are projected.
                if(!Set.of("RANKED_SOLO_5x5","RANKED_FLEX_SR").contains(queue))continue;
                String tier=row.path("tier").asText(""); String division=row.path("rank").asText("");
                if(!Set.of("IRON","BRONZE","SILVER","GOLD","PLATINUM","EMERALD","DIAMOND","MASTER","GRANDMASTER","CHALLENGER").contains(tier)
                        || !Set.of("I","II","III","IV").contains(division) || !row.path("leaguePoints").isIntegralNumber()
                        || !row.path("leaguePoints").canConvertToInt() || row.path("leaguePoints").intValue()<0) throw new IllegalArgumentException();
                if(values.containsKey(queue)) throw new IllegalArgumentException();
                Integer wins=optionalCount(row,"wins"),losses=optionalCount(row,"losses");
                values.put(queue,new RankSnapshotStore.Value("ranked",tier,division,row.path("leaguePoints").intValue(),wins,losses));
            }
            synchronized(this) {
                var current=cache.get(key);if(current==null||!refreshId.equals(current.refreshId))return;
                long fetched=clock.getAsLong();
                if(store!=null)store.success(key.platform(),key.puuid(),Map.copyOf(values),Instant.ofEpochMilli(fetched),refreshId);
                var entry=cache.get(key);if(entry==null)return;entry.values=Map.copyOf(values);entry.fetchedAt=fetched;entry.retryAt=0;entry.error=null;entry.pending=false;
                if(store!=null){var saved=store.read(key.platform(),key.puuid()).orElse(null);entry.values=saved==null?null:saved.values();entry.fetchedAt=saved==null||saved.fetchedAt()==null?0:saved.fetchedAt().toEpochMilli();}
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
    private Integer optionalCount(tools.jackson.databind.JsonNode row,String field) {
        var node=row.get(field);if(node==null||node.isNull())return null;
        if(!node.isIntegralNumber()||!node.canConvertToInt()||node.intValue()<0)throw new IllegalArgumentException();
        return node.intValue();
    }
    private void fail(Key key,String error,long retry) {
        var entry=cache.get(key);if(entry==null)return;entry.pending=false;entry.error=error;entry.retryAt=retry;
        if(store!=null)store.failure(key.platform(),key.puuid(),error,Instant.ofEpochMilli(retry),entry.refreshId);
    }
    @PreDestroy void close() { if(executor instanceof ExecutorService service) service.shutdownNow(); }
    public record State(RankSnapshotStore.Value value,Instant fetchedAt,boolean refreshing,boolean stale,Instant retryNotBefore,String error) {}
    private record Key(String platform,String puuid) {}
    private static final class Entry { Map<String,RankSnapshotStore.Value> values;long fetchedAt;long retryAt;boolean pending;String error;UUID refreshId;long leaseUntil; }
}
