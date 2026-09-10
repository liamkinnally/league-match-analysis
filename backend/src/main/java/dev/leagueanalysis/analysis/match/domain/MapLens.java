package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import java.util.List;
import java.util.Objects;

public record MapLens(List<Point> points, List<EvidenceClaim> claims) implements LensPayload {
    public MapLens {
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        if (points.isEmpty()) {
            throw new IllegalArgumentException("MAP_POINT_REQUIRED");
        }
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
    }

    @Override
    public LensKind kind() {
        return LensKind.MAP;
    }

    public record Point(
            long representedAtMs,
            int x,
            int y,
            AnchorKind anchorKind,
            Integer participantId,
            String descriptor,
            List<EvidenceReference> evidenceReferences) {
        public Point {
            if (representedAtMs < 0) {
                throw new IllegalArgumentException("NEGATIVE_REPRESENTED_TIME");
            }
            anchorKind = Objects.requireNonNull(anchorKind, "anchorKind");
            evidenceReferences = List.copyOf(
                    Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
            if (evidenceReferences.isEmpty()) {
                throw new IllegalArgumentException("MISSING_EVIDENCE_REFERENCE");
            }
        }
    }
}
