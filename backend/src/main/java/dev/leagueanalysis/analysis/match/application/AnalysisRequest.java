package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.LensKind;

public record AnalysisRequest(
        String matchId,
        int focalParticipantId,
        String selectedObjectId,
        Long intervalStartMs,
        Long intervalEndMs,
        String questionId,
        String requestedEvidenceRevision,
        LensKind requestedLens) {
    public AnalysisRequest {
        if (matchId == null || matchId.isBlank()
                || focalParticipantId < 1 || focalParticipantId > 10) {
            throw MatchAnalysisException.invalidRequest();
        }
        var calm = selectedObjectId == null
                && intervalStartMs == null
                && intervalEndMs == null
                && questionId == null
                && requestedEvidenceRevision == null
                && requestedLens == null;
        if (!calm && (selectedObjectId == null
                || intervalStartMs == null
                || intervalEndMs == null
                || questionId == null
                || requestedEvidenceRevision == null
                || requestedLens == null)) {
            throw MatchAnalysisException.invalidRequest();
        }
        if (!calm && (!selectedObjectId.matches("(?:trn|ep)_[0-9a-f]{24}")
                || intervalStartMs < 0
                || intervalEndMs < intervalStartMs
                || questionId.isBlank()
                || !requestedEvidenceRevision.matches("ev_[0-9a-f]{64}"))) {
            throw MatchAnalysisException.invalidRequest();
        }
    }

    public boolean calm() {
        return selectedObjectId == null;
    }
}
