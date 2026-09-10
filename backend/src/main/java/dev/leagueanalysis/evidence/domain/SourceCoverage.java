package dev.leagueanalysis.evidence.domain;

import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

public record SourceCoverage(
        String signal,
        String sourceKind,
        CoverageStatus status,
        Long representedStartMs,
        Long representedEndMs,
        UUID sourceRecordId,
        UUID sourceCaptureId,
        String methodVersion) implements Comparable<SourceCoverage> {
    public static final Comparator<SourceCoverage> STABLE_ORDER = Comparator
            .comparing(SourceCoverage::signal)
            .thenComparing(SourceCoverage::sourceKind)
            .thenComparing(SourceCoverage::methodVersion)
            .thenComparing(coverage -> coverage.status().name())
            .thenComparing(SourceCoverage::representedStartMs, Comparator.nullsFirst(Long::compareTo))
            .thenComparing(SourceCoverage::representedEndMs, Comparator.nullsFirst(Long::compareTo))
            .thenComparing(SourceCoverage::sourceCaptureId, Comparator.nullsFirst(UUID::compareTo))
            .thenComparing(SourceCoverage::sourceRecordId);

    public SourceCoverage {
        signal = TimelineEventKey.requireText(signal);
        sourceKind = TimelineEventKey.requireText(sourceKind);
        status = Objects.requireNonNull(status, "status");
        sourceRecordId = Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        methodVersion = TimelineEventKey.requireText(methodVersion);
        if (representedStartMs != null && representedStartMs < 0
                || representedEndMs != null && representedEndMs < 0
                || representedStartMs != null && representedEndMs != null
                        && representedEndMs < representedStartMs) {
            throw new IllegalArgumentException("INVALID_COVERAGE_BOUNDS");
        }
        if (status != CoverageStatus.UNKNOWN && status != CoverageStatus.UNAVAILABLE) {
            sourceCaptureId = Objects.requireNonNull(sourceCaptureId, "sourceCaptureId");
        }
    }

    @Override
    public int compareTo(SourceCoverage other) {
        return STABLE_ORDER.compare(this, Objects.requireNonNull(other, "other"));
    }
}
