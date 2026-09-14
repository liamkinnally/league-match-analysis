package dev.leagueanalysis.analysis.match.domain;

import java.util.List;

/** Public end-of-game rune selections and source counters; private capture identifiers stay server-side. */
public record ParticipantRunes(String availability, String matchPatch, String normalizationVersion,
        String layoutManifestId, String performanceStatus, List<Style> styles, Shards shards) {
    public record Style(Integer styleId, String role, List<Selection> selections) {}
    public record Selection(int runeId, List<Metric> metrics, Counters counters) {}
    public record Counters(Integer var1, Integer var2, Integer var3) {}
    public record Shards(Integer offense, Integer flex, Integer defense) {}
    public record Metric(String id, String label, String availability, Double value, String unit,
            String targetScope, String timeScope, String valueBasis, String mappingVersion) {}
}
