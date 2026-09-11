package dev.leagueanalysis.privacy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Private operator report. The implementation details are deliberately not public accessors. */
public final class RemovalPlan {
    private final Set<String> puuidHashes;
    private final Set<String> aliasHashes;
    private final Set<String> matchHashes;
    private final List<String> matchIds;
    private final Map<String, Long> affectedRecords;
    private final String fingerprint;
    final RemovalPlanner.Changes changes;

    RemovalPlan(Set<String> puuidHashes, Set<String> aliasHashes, Set<String> matchHashes,
            List<String> matchIds, Map<String, Long> affectedRecords, String fingerprint,
            RemovalPlanner.Changes changes) {
        this.puuidHashes = Set.copyOf(puuidHashes);
        this.aliasHashes = Set.copyOf(aliasHashes);
        this.matchHashes = Set.copyOf(matchHashes);
        this.matchIds = List.copyOf(matchIds);
        this.affectedRecords = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(affectedRecords));
        this.fingerprint = fingerprint;
        this.changes = changes;
    }
    public Set<String> puuidHashes() { return puuidHashes; }
    public Set<String> aliasHashes() { return aliasHashes; }
    public Set<String> matchHashes() { return matchHashes; }
    public List<String> matchIds() { return matchIds; }
    public Map<String, Long> affectedRecords() { return affectedRecords; }
    public String fingerprint() { return fingerprint; }
}
