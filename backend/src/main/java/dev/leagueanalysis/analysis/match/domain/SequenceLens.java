package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.List;
import java.util.Objects;

public record SequenceLens(
        List<Band> bands,
        List<Relation> relations,
        List<EvidenceClaim> claims) implements LensPayload {
    public SequenceLens {
        bands = List.copyOf(Objects.requireNonNull(bands, "bands"));
        if (bands.isEmpty()) {
            throw new IllegalArgumentException("SEQUENCE_BAND_REQUIRED");
        }
        relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
    }

    @Override
    public LensKind kind() {
        return LensKind.SEQUENCE;
    }

    public enum RelationKind {
        BEFORE,
        AFTER,
        OVERLAPS,
        CO_OCCURS,
        PARTICIPATED
    }

    public record Band(
            String id,
            long startMs,
            long endMs,
            List<MatchAnchor> anchors,
            boolean parallel) {
        public Band {
            id = TimelineEventKey.requireText(id);
            if (startMs < 0 || endMs < startMs) {
                throw new IllegalArgumentException("INVALID_SEQUENCE_BAND");
            }
            anchors = List.copyOf(Objects.requireNonNull(anchors, "anchors"));
            if (anchors.isEmpty()) {
                throw new IllegalArgumentException("SEQUENCE_ANCHOR_REQUIRED");
            }
        }
    }

    public record Relation(
            String fromBandId,
            String toBandId,
            RelationKind kind,
            List<EvidenceReference> evidenceReferences) {
        public Relation {
            fromBandId = TimelineEventKey.requireText(fromBandId);
            toBandId = TimelineEventKey.requireText(toBandId);
            kind = Objects.requireNonNull(kind, "kind");
            evidenceReferences = List.copyOf(
                    Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
        }
    }
}
