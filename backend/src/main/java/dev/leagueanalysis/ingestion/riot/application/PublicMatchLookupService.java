package dev.leagueanalysis.ingestion.riot.application;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Bounded, round-robin work on one backend instance; stored pages survive restart. */
@Service
public class PublicMatchLookupService {
    private final RiotIngestionService ingestion;
    private dev.leagueanalysis.analysis.profile.PlayerProfileService profiles;
    @Autowired void profileService(dev.leagueanalysis.analysis.profile.PlayerProfileService profiles) { this.profiles=profiles; }
    private final PublicMatchLookupStore store;
    private final Clock clock;
    private final boolean enabled;
    private final Executor worker;
    private final ScheduledExecutorService timer;
    private final BiConsumer<Runnable, Duration> later;
    private final Map<RequestKey, UUID> active = new HashMap<>();
    private final Map<UUID, RiotIngestionCommand> pendingIdentities = new HashMap<>();
    private final Map<String, List<Instant>> submissions = new HashMap<>();

    @Autowired
    public PublicMatchLookupService(RiotIngestionService ingestion, PublicMatchLookupStore store, Clock clock,
            @Value("${league-analysis.riot.public-lookup-enabled:false}") boolean enabled) {
        this(ingestion, store, clock, enabled, newScheduler());
    }

    PublicMatchLookupService(RiotIngestionService ingestion, PublicMatchLookupStore store, Clock clock,
            boolean enabled, Executor worker) {
        this(ingestion, store, clock, enabled, worker, null);
    }

    PublicMatchLookupService(RiotIngestionService ingestion, PublicMatchLookupStore store, Clock clock,
            boolean enabled, Executor worker, BiConsumer<Runnable, Duration> later) {
        this.ingestion = ingestion; this.store = store; this.clock = clock; this.enabled = enabled; this.worker = worker;
        this.timer = later == null ? worker instanceof ScheduledExecutorService scheduled ? scheduled : newScheduler() : null;
        this.later = later == null ? (task, delay) -> timer.schedule(() -> worker.execute(task), delay.toMillis(), TimeUnit.MILLISECONDS) : later;
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "public-match-lookup");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct public void interruptPreviousRuns() { store.failInterrupted(clock.instant()); }
    @PreDestroy public void close() {
        if (worker instanceof ExecutorService executor) executor.shutdownNow();
        if (timer != null && timer != worker) timer.shutdownNow();
    }

    public Submission submit(String gameName, String tagLine, String peer) { return submit(gameName, tagLine, 0, peer); }

    public synchronized Submission submit(String gameName, String tagLine, int queueId, String peer) {
        var command = firstPage(gameName, tagLine, queueId);
        var running = active.get(key(command));
        if (running != null) return new Submission(202, get(running));
        var cached = store.findFresh(command, Instant.EPOCH);
        if (cached.isPresent()) return new Submission(200, cached.get());
        return start(command, peer);
    }

    public synchronized Submission refresh(UUID runId, String peer) {
        var previous = pageCommand(runId);
        var command = firstPage(previous.gameName(), previous.tagLine(), previous.queueId());
        var running = active.get(key(command));
        if (running != null) return new Submission(202, get(running));
        var next = store.latestRefresh(command).map(time -> time.plusSeconds(900)).filter(time -> time.isAfter(clock.instant()));
        if (next.isPresent()) throw new PublicLookupException(429, "This account was updated recently. Try again after the indicated time.", next.get());
        return start(command, peer);
    }

    public synchronized Submission older(UUID runId, String peer) {
        var previous = pageCommand(runId);
        var cached = store.findOlder(runId);
        if (cached.isPresent()) return new Submission(200, cached.get());
        var page = get(runId);
        if (page.status().equals("RUNNING")) return new Submission(202, page);
        if (!page.hasMore()) return new Submission(200, page);
        var command = new RiotIngestionCommand(previous.gameName(), previous.tagLine(), 20, previous.queueId(),
                Math.addExact(previous.start(), previous.matchLimit()), previous.endTime(), runId);
        var running = active.get(key(command));
        return running == null ? start(command, peer) : new Submission(202, get(running));
    }

    /** Bounded recent work shares history admission, identity verification, cooldown and round-robin turns. */
    public synchronized Submission recentRecord(UUID runId,String peer) {
        var previous=pageCommand(runId);
        var command=new RiotIngestionCommand(previous.gameName(),previous.tagLine(),20,420,0,clock.instant().getEpochSecond(),runId);
        var requestKey=new RequestKey("RECENT",profiles==null?previous.gameName().toLowerCase(Locale.ROOT):profiles.identityKey(runId),"",420,null);
        var running=active.get(requestKey);
        if(running!=null)return new Submission(202,get(running));
        var next=store.latestRefresh(command).map(time->time.plusSeconds(900)).filter(time->time.isAfter(clock.instant()));
        if(next.isPresent())throw new PublicLookupException(429,"This account was updated recently. Try again after the indicated time.",next.get());
        checkAdmission(peer);
        if(profiles!=null)profiles.claimRecent(runId);
        var result=start(command,peer,requestKey);
        if(profiles!=null)profiles.bindRecent(runId,result.lookup().runId());
        return result;
    }

    private RiotIngestionCommand pageCommand(UUID runId) {
        return store.readPageCommand(runId).orElseThrow(() -> new PublicLookupException(404, "History page not found. Search again.", null));
    }

    private RiotIngestionCommand firstPage(String name, String tag, int queue) {
        var command = new RiotIngestionCommand(name, tag, 20, queue, 0, clock.instant().getEpochSecond(), null);
        if (command.gameName().codePoints().anyMatch(Character::isISOControl)
                || command.tagLine().codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("INVALID_RIOT_ID");
        return command;
    }

    private Submission start(RiotIngestionCommand command, String peer) {return start(command,peer,key(command));}
    private Submission start(RiotIngestionCommand command,String peer,RequestKey key) {
        checkAdmission(peer);
        var now = clock.instant();
        var id = store.startPublicRun(command, now);
        active.put(key, id);
        pendingIdentities.put(id, command);
        var history=ingestion.historyWork(id, command, store);
        PublicIngestionWork work=profiles==null?history:new HistoryWithProfile(id,history,now.plusSeconds(900));
        enqueue(key, id, work, now.plusSeconds(900));
        recordAdmission(peer, now);
        return new Submission(202, get(id));
    }

    private final class HistoryWithProfile implements PublicIngestionWork {
        private final UUID id;
        private final PublicIngestionWork history;
        private final Instant deadline;
        private boolean historyComplete;
        private boolean pendingRegistered;
        private PublicIngestionWork profile;
        private HistoryWithProfile(UUID id,PublicIngestionWork history,Instant deadline) {
            this.id=id;this.history=history;this.deadline=deadline;
        }
        public boolean step() {
            if(!historyComplete) {
                historyComplete=history.step();
                if(!pendingRegistered)pendingRegistered=profiles.markPending(id,deadline);
                return false;
            }
            if(profile==null)profile=profiles.refreshWork(id);
            boolean complete;
            try {complete=profile.step();}
            catch(RiotGatewayException failure) {
                if(failure.code()==RiotFailureCode.RATE_LIMITED)throw failure;
                complete=true;
            }
            catch(RuntimeException unavailable) {complete=true;}
            if(complete)profiles.finishWork(id);
            return complete;
        }
    }

    public synchronized TimelineLookup timeline(String matchId) {
        validateMatchId(matchId);
        return store.readTimeline(matchId).orElseThrow(() -> new PublicLookupException(404, "Match not found.", null));
    }

    public synchronized TimelineLookup requestTimeline(String matchId, String peer) {
        var current = timeline(matchId);
        if (current.status().equals("AVAILABLE") || current.status().equals("RUNNING") || current.status().equals("UNAVAILABLE")) return current;
        if (current.retryNotBefore() != null && current.retryNotBefore().isAfter(clock.instant())) return current;
        checkAdmission(peer);
        var now = clock.instant();
        var id = store.startTimelineRun(matchId, now);
        var key = new RequestKey("TIMELINE", matchId, "", 0, null);
        active.put(key, id);
        enqueue(key, id, ingestion.timelineWork(id, matchId, store), now.plusSeconds(900));
        recordAdmission(peer, now);
        return timeline(matchId);
    }

    private void checkAdmission(String peer) {
        if (!enabled) throw new PublicLookupException(503, "Live lookup is unavailable. Explore the sample match.", null);
        var now = clock.instant();
        store.latestCooldown().filter(time -> time.isAfter(now)).ifPresent(time -> {
            throw new PublicLookupException(429, "Riot is cooling down. Try again after the indicated time.", time);
        });
        if (active.size() >= 5) throw busy(now.plusSeconds(2));
        // The socket peer is trusted; forwarded browser headers are not used as identities.
        submissions.values().forEach(times -> times.removeIf(time -> !time.isAfter(now.minusSeconds(60))));
        submissions.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        if (!submissions.containsKey(peer) && submissions.size() >= 4096) throw busy(now.plusSeconds(60));
        var times = submissions.getOrDefault(peer, List.of());
        if (times.size() >= 6) throw new PublicLookupException(429, "Too many lookups from this connection. Try again shortly.", times.getFirst().plusSeconds(60));
    }

    private void recordAdmission(String peer, Instant now) { submissions.computeIfAbsent(peer, ignored -> new ArrayList<>()).add(now); }

    private void enqueue(RequestKey key, UUID id, PublicIngestionWork work, Instant deadline) {
        try { worker.execute(() -> turn(key, id, work, deadline)); }
        catch (RejectedExecutionException failure) { fail(key, id, work); }
    }

    private void turn(RequestKey key, UUID id, PublicIngestionWork work, Instant deadline) {
        synchronized (this) { if (!id.equals(active.get(key))) return; }
        try {
            if (!clock.instant().isBefore(deadline)) { fail(key, id, work); return; }
            var cooldown = store.latestCooldown().filter(time -> time.isAfter(clock.instant()));
            if (cooldown.isPresent()) { defer(key, id, work, deadline, cooldown.get()); return; }
            if (work.step()) release(key, id);
            else enqueue(key, id, work, deadline);
        } catch (RiotGatewayException failure) {
            if (failure.code() == RiotFailureCode.RATE_LIMITED) {
                var retry = failure.retryNotBefore() == null ? clock.instant().plusSeconds(60) : failure.retryNotBefore();
                defer(key, id, work, deadline, retry);
            } else fail(key, id, work);
        } catch (RuntimeException failure) { fail(key, id, work); }
    }

    private void defer(RequestKey key, UUID id, PublicIngestionWork work, Instant deadline, Instant retry) {
        var safeRetry = retry.isAfter(clock.instant()) ? retry : clock.instant().plusSeconds(1);
        ingestion.recordPublicCooldown(id, safeRetry);
        var wake = safeRetry.isBefore(deadline) ? safeRetry : deadline;
        later.accept(() -> turn(key, id, work, deadline), Duration.between(clock.instant(), wake).isNegative()
                ? Duration.ZERO : Duration.between(clock.instant(), wake));
    }
    private void fail(RequestKey key, UUID id, PublicIngestionWork work) {
        try {
            // Profile work cannot change a history result which already finished.
            if(!(work instanceof HistoryWithProfile continuation)||!continuation.historyComplete)
                store.failPublicRun(id, clock.instant());
        } finally {
            try {if(work instanceof HistoryWithProfile)profiles.finishWork(id);}
            finally {release(key, id);}
        }
    }
    private synchronized void release(RequestKey key, UUID id) { active.remove(key, id); pendingIdentities.remove(id); }

    public synchronized PublicMatchLookup get(UUID runId) {
        var lookup = store.readPublicRun(runId).orElseThrow(() -> new PublicLookupException(404, "Lookup not found. Search again.", null));
        var pending = pendingIdentities.get(runId);
        return pending != null && lookup.gameName().isEmpty() && lookup.tagLine().isEmpty()
                ? lookup.withIdentity(pending.gameName(), pending.tagLine()) : lookup;
    }
    private static void validateMatchId(String id) {
        if (id == null || !id.matches("NA1_[0-9]{1,20}")) throw new IllegalArgumentException("INVALID_MATCH_ID");
    }
    private PublicLookupException busy(Instant retry) { return new PublicLookupException(429, "Lookup is busy. Try again shortly.", retry); }
    private RequestKey key(RiotIngestionCommand c) {
        return new RequestKey("HISTORY", c.gameName().toLowerCase(Locale.ROOT), c.tagLine().toLowerCase(Locale.ROOT), c.queueId(), c.previousRunId());
    }
    private record RequestKey(String kind, String name, String tag, int queue, UUID previous) {}
    public record Submission(int httpStatus, PublicMatchLookup lookup) {}
}
