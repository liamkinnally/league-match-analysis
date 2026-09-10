package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record EvidenceClaim(
        String claimId,
        String statement,
        AssertionMode assertionMode,
        List<EvidenceReference> evidenceReferences,
        Set<String> limitationCodes) {
    public EvidenceClaim {
        claimId = TimelineEventKey.requireText(claimId);
        statement = TimelineEventKey.requireText(statement);
        assertionMode = Objects.requireNonNull(assertionMode, "assertionMode");
        evidenceReferences = Objects.requireNonNull(evidenceReferences, "evidenceReferences")
                .stream()
                .map(reference -> Objects.requireNonNull(reference, "evidenceReference"))
                .distinct()
                .sorted(Comparator.comparingLong(EvidenceReference::representedAtMs)
                        .thenComparing(EvidenceReference::sourceCaptureId)
                        .thenComparing(EvidenceReference::sourceRecordId)
                        .thenComparing(EvidenceReference::methodVersion))
                .toList();
        var limitations = new TreeSet<>(
                Objects.requireNonNull(limitationCodes, "limitationCodes"));
        if (limitations.stream().anyMatch(code -> code == null || code.isBlank())) {
            throw new IllegalArgumentException("INVALID_CLAIM_LIMITATION");
        }
        limitationCodes = Collections.unmodifiableSortedSet(limitations);
    }
}
