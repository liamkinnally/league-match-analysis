package dev.leagueanalysis.evidence.domain;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

public record InventorySnapshot(
        int participantId,
        SortedMap<Integer, Integer> itemQuantities,
        CoverageStatus coverageStatus,
        List<EvidenceReference> supportingEvidence,
        String ruleVersion,
        List<InventoryAmbiguity> ambiguities,
        Set<String> limitationCodes) {
    public InventorySnapshot {
        if (participantId < 1) {
            throw new IllegalArgumentException("INVALID_PARTICIPANT_ID");
        }
        var quantities = new TreeMap<Integer, Integer>();
        for (var entry : Objects.requireNonNull(itemQuantities, "itemQuantities").entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 1 || entry.getValue() == null || entry.getValue() < 1) {
                throw new IllegalArgumentException("INVALID_ITEM_QUANTITY");
            }
            quantities.put(entry.getKey(), entry.getValue());
        }
        itemQuantities = Collections.unmodifiableSortedMap(quantities);
        coverageStatus = Objects.requireNonNull(coverageStatus, "coverageStatus");
        supportingEvidence = List.copyOf(Objects.requireNonNull(supportingEvidence, "supportingEvidence"));
        ruleVersion = TimelineEventKey.requireText(ruleVersion);
        ambiguities = List.copyOf(Objects.requireNonNull(ambiguities, "ambiguities"));
        limitationCodes = Collections.unmodifiableSortedSet(
                new TreeSet<>(Objects.requireNonNull(limitationCodes, "limitationCodes")));
    }
}
