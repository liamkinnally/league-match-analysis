package dev.leagueanalysis.evidence.domain;

import java.util.Objects;
import java.util.UUID;

public record MatchSourceRevision(
        String matchId,
        int mapId,
        UUID detailCaptureId,
        UUID timelineCaptureId,
        String materializationVersion) {
    public MatchSourceRevision {
        matchId = TimelineEventKey.requireText(matchId);
        detailCaptureId = Objects.requireNonNull(detailCaptureId, "detailCaptureId");
        materializationVersion = TimelineEventKey.requireText(materializationVersion);
    }
}
