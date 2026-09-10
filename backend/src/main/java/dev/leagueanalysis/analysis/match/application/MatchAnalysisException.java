package dev.leagueanalysis.analysis.match.application;

import java.util.Objects;
import java.util.Optional;

public final class MatchAnalysisException extends RuntimeException {
    private final Code code;
    private final String currentEvidenceRevision;

    private MatchAnalysisException(
            Code code, String message, String currentEvidenceRevision) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.currentEvidenceRevision = currentEvidenceRevision;
    }

    public static MatchAnalysisException invalidRequest() {
        return new MatchAnalysisException(
                Code.INVALID_ANALYSIS_REQUEST,
                "The analysis request is invalid.",
                null);
    }

    public static MatchAnalysisException focalParticipantNotFound() {
        return new MatchAnalysisException(
                Code.FOCAL_PARTICIPANT_NOT_FOUND,
                "The focal participant was not found in this match.",
                null);
    }

    public static MatchAnalysisException analyticalObjectNotFound() {
        return new MatchAnalysisException(
                Code.ANALYTICAL_OBJECT_NOT_FOUND,
                "The analytical object was not found in this evidence revision.",
                null);
    }

    public static MatchAnalysisException staleRevision(String currentEvidenceRevision) {
        return new MatchAnalysisException(
                Code.STALE_EVIDENCE_REVISION,
                "The match evidence changed. Refresh this analysis before continuing.",
                Objects.requireNonNull(currentEvidenceRevision, "currentEvidenceRevision"));
    }

    public Code code() {
        return code;
    }

    public Optional<String> currentEvidenceRevision() {
        return Optional.ofNullable(currentEvidenceRevision);
    }

    public enum Code {
        INVALID_ANALYSIS_REQUEST,
        FOCAL_PARTICIPANT_NOT_FOUND,
        ANALYTICAL_OBJECT_NOT_FOUND,
        STALE_EVIDENCE_REVISION
    }
}
