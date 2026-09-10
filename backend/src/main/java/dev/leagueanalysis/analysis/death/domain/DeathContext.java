package dev.leagueanalysis.analysis.death.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.InventoryAmbiguity;
import dev.leagueanalysis.evidence.domain.InventorySnapshot;
import dev.leagueanalysis.evidence.domain.MatchSourceRevision;
import dev.leagueanalysis.evidence.domain.SourceCoverage;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record DeathContext(
        DeathEvent death,
        MatchSourceRevision sourceRevision,
        List<ParticipantStateAtDeath> participantStates,
        List<InventorySnapshot> inventories,
        List<InventoryAmbiguity> inventoryAmbiguities,
        List<ObjectiveRelationship> objectiveRelationships,
        List<SourceCoverage> inputCoverage,
        Set<String> ruleVersions,
        Set<String> limitationCodes,
        List<EvidenceReference> evidenceReferences) {
    public DeathContext {
        death = Objects.requireNonNull(death, "death");
        sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
        if (!death.key().matchId().equals(sourceRevision.matchId())) {
            throw new IllegalArgumentException("MISMATCHED_DEATH_CONTEXT_MATCH");
        }
        participantStates = Objects.requireNonNull(participantStates, "participantStates").stream()
                .map(state -> Objects.requireNonNull(state, "participantState"))
                .sorted(Comparator.comparingInt(ParticipantStateAtDeath::participantId))
                .toList();
        inventories = Objects.requireNonNull(inventories, "inventories").stream()
                .map(inventory -> Objects.requireNonNull(inventory, "inventory"))
                .sorted(Comparator.comparingInt(InventorySnapshot::participantId))
                .toList();
        inventoryAmbiguities = Objects.requireNonNull(inventoryAmbiguities, "inventoryAmbiguities").stream()
                .map(ambiguity -> Objects.requireNonNull(ambiguity, "inventoryAmbiguity"))
                .sorted(Comparator.comparing((InventoryAmbiguity ambiguity) -> ambiguity.transition().key())
                        .thenComparing(InventoryAmbiguity::code)
                        .thenComparing(ambiguity -> ambiguity.transition().evidence().sourceCaptureId())
                        .thenComparing(ambiguity -> ambiguity.transition().evidence().sourceRecordId()))
                .toList();
        objectiveRelationships = Objects.requireNonNull(objectiveRelationships, "objectiveRelationships").stream()
                .map(relationship -> Objects.requireNonNull(relationship, "objectiveRelationship"))
                .sorted(Comparator.comparing((ObjectiveRelationship relationship) -> relationship.objective().key())
                        .thenComparing(relationship -> relationship.objective().evidence().sourceCaptureId())
                        .thenComparing(relationship -> relationship.objective().evidence().sourceRecordId()))
                .toList();
        inputCoverage = Objects.requireNonNull(inputCoverage, "inputCoverage").stream()
                .map(coverage -> Objects.requireNonNull(coverage, "inputCoverageRecord"))
                .sorted()
                .toList();
        ruleVersions = immutableTextSet(ruleVersions, "ruleVersions");
        limitationCodes = immutableTextSet(limitationCodes, "limitationCodes");
        evidenceReferences = Objects.requireNonNull(evidenceReferences, "evidenceReferences").stream()
                .map(reference -> Objects.requireNonNull(reference, "evidenceReference"))
                .distinct()
                .sorted(Comparator.comparingLong(EvidenceReference::representedAtMs)
                        .thenComparing(EvidenceReference::sourceCaptureId)
                        .thenComparing(EvidenceReference::sourceRecordId)
                        .thenComparing(EvidenceReference::methodVersion))
                .toList();
    }

    private static Set<String> immutableTextSet(Set<String> values, String fieldName) {
        var result = new TreeSet<String>();
        for (var value : Objects.requireNonNull(values, fieldName)) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("INVALID_" + fieldName.toUpperCase());
            }
            result.add(value);
        }
        return Collections.unmodifiableSortedSet(result);
    }
}
