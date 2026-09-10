package dev.leagueanalysis.analysis.match.adapter.in.web;

import dev.leagueanalysis.analysis.match.application.MatchAnalysisException;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {
    MatchAnalysisController.class,
    MatchDevelopmentController.class
})
public final class MatchAnalysisExceptionHandler {
    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        ConversionFailedException.class
    })
    ResponseEntity<MatchAnalysisErrorResponse> invalidBinding(Exception exception) {
        return response(
                HttpStatus.BAD_REQUEST,
                new MatchAnalysisErrorResponse(
                        "INVALID_ANALYSIS_REQUEST",
                        "The analysis request is invalid.",
                        null));
    }

    @ExceptionHandler(MatchNotFoundException.class)
    ResponseEntity<MatchAnalysisErrorResponse> matchNotFound(
            MatchNotFoundException exception) {
        return response(
                HttpStatus.NOT_FOUND,
                new MatchAnalysisErrorResponse(
                        "MATCH_NOT_FOUND",
                        "The match was not found.",
                        null));
    }

    @ExceptionHandler(MatchAnalysisException.class)
    ResponseEntity<MatchAnalysisErrorResponse> analysisFailure(
            MatchAnalysisException exception) {
        var status = switch (exception.code()) {
            case INVALID_ANALYSIS_REQUEST -> HttpStatus.BAD_REQUEST;
            case FOCAL_PARTICIPANT_NOT_FOUND, ANALYTICAL_OBJECT_NOT_FOUND ->
                    HttpStatus.NOT_FOUND;
            case STALE_EVIDENCE_REVISION -> HttpStatus.CONFLICT;
        };
        return response(
                status,
                new MatchAnalysisErrorResponse(
                        exception.code().name(),
                        exception.getMessage(),
                        exception.currentEvidenceRevision().orElse(null)));
    }

    private ResponseEntity<MatchAnalysisErrorResponse> response(
            HttpStatus status, MatchAnalysisErrorResponse body) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}

record MatchAnalysisErrorResponse(
        String code,
        String message,
        String currentEvidenceRevision) {}

final class MatchNotFoundException extends RuntimeException {}
