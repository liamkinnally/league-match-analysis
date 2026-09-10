package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.List;
import java.util.Objects;

public record TransferLens(List<Row> rows, List<EvidenceClaim> claims)
        implements LensPayload {
    public TransferLens {
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("TRANSFER_ROW_REQUIRED");
        }
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
    }

    @Override
    public LensKind kind() {
        return LensKind.TRANSFER;
    }

    public record Row(
            long representedAtMs,
            String category,
            String observedFor,
            List<EvidenceReference> evidenceReferences) {
        public Row {
            if (representedAtMs < 0) {
                throw new IllegalArgumentException("NEGATIVE_REPRESENTED_TIME");
            }
            category = TimelineEventKey.requireText(category);
            observedFor = TimelineEventKey.requireText(observedFor);
            evidenceReferences = List.copyOf(
                    Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
            if (evidenceReferences.isEmpty()) {
                throw new IllegalArgumentException("MISSING_EVIDENCE_REFERENCE");
            }
        }
    }
}
