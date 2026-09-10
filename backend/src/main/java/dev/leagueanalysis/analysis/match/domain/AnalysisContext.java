package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.List;
import java.util.Objects;

public record AnalysisContext(
        String matchId,
        int focalParticipantId,
        String selectedObjectId,
        TimeInterval interval,
        String questionId,
        String evidenceRevision,
        LensKind primaryLens,
        LensKind selectedLens,
        List<LensKind> availableLenses) {
    public AnalysisContext {
        matchId = TimelineEventKey.requireText(matchId);
        if (focalParticipantId < 1 || focalParticipantId > 10) {
            throw new IllegalArgumentException("INVALID_PARTICIPANT_ID");
        }
        selectedObjectId = TimelineEventKey.requireText(selectedObjectId);
        interval = Objects.requireNonNull(interval, "interval");
        questionId = TimelineEventKey.requireText(questionId);
        evidenceRevision = TimelineEventKey.requireText(evidenceRevision);
        primaryLens = Objects.requireNonNull(primaryLens, "primaryLens");
        selectedLens = Objects.requireNonNull(selectedLens, "selectedLens");
        availableLenses = List.copyOf(
                Objects.requireNonNull(availableLenses, "availableLenses"));
        if (!availableLenses.contains(LensKind.RECEIPT)
                || !availableLenses.contains(primaryLens)
                || !availableLenses.contains(selectedLens)) {
            throw new IllegalArgumentException("INVALID_ANALYSIS_LENSES");
        }
    }
}
