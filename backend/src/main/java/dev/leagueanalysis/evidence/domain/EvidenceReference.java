package dev.leagueanalysis.evidence.domain;

import java.util.Objects;
import java.util.UUID;

public record EvidenceReference(
        UUID sourceCaptureId,
        UUID sourceRecordId,
        long representedAtMs,
        String methodVersion) {
    public EvidenceReference {
        sourceCaptureId = Objects.requireNonNull(sourceCaptureId, "sourceCaptureId");
        sourceRecordId = Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        methodVersion = TimelineEventKey.requireText(methodVersion);
        if (representedAtMs < 0) {
            throw new IllegalArgumentException("NEGATIVE_REPRESENTED_TIME");
        }
    }
}
