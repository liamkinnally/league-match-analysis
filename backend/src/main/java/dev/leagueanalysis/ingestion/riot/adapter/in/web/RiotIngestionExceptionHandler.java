package dev.leagueanalysis.ingestion.riot.adapter.in.web;

import dev.leagueanalysis.ingestion.riot.application.RiotGatewayException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RiotIngestionController.class)
@Profile("local")
public class RiotIngestionExceptionHandler {
    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<RiotIngestionError> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new RiotIngestionError("INVALID_REQUEST", "Request validation failed"));
    }

    @ExceptionHandler(RiotGatewayException.class)
    ResponseEntity<RiotIngestionError> riotFailure(RiotGatewayException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new RiotIngestionError(exception.code().name(), "Riot ingestion request failed"));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<RiotIngestionError> unexpectedFailure(RuntimeException exception) {
        return ResponseEntity.internalServerError()
                .body(new RiotIngestionError("INGESTION_FAILED", "Ingestion request failed"));
    }
}

record RiotIngestionError(String code, String message) {}
