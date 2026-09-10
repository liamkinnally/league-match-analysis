package dev.leagueanalysis.ingestion.riot.adapter.in.web;

import dev.leagueanalysis.ingestion.riot.application.PublicLookupException;
import dev.leagueanalysis.ingestion.riot.application.PublicMatchLookup;
import dev.leagueanalysis.ingestion.riot.application.PublicMatchLookupService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/player-matches")
public class PublicMatchLookupController {
    private final PublicMatchLookupService service;
    private final Clock clock;
    public PublicMatchLookupController(PublicMatchLookupService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<PublicMatchLookup> submit(@RequestBody Request request, HttpServletRequest servlet) {
        var result = service.submit(request.gameName(), request.tagLine(), servlet.getRemoteAddr());
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.lookup());
    }

    @GetMapping("/{runId}")
    public ResponseEntity<PublicMatchLookup> get(@PathVariable UUID runId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.get(runId));
    }

    @ExceptionHandler(PublicLookupException.class)
    public ResponseEntity<Error> lookupFailure(PublicLookupException exception) {
        var response = ResponseEntity.status(exception.status()).header("Cache-Control", "no-store");
        if (exception.retryNotBefore() != null) response.header("Retry-After", Long.toString(
                Math.max(1, (Duration.between(clock.instant(), exception.retryNotBefore()).toMillis() + 999) / 1000)));
        return response.body(new Error(exception.getMessage(), exception.retryNotBefore()));
    }

    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Error> invalidRequest(Exception ignored) {
        return ResponseEntity.badRequest().body(new Error("Enter a Riot game name and tag line.", null));
    }

    public record Request(String gameName, String tagLine) {}
    public record Error(String message, Instant retryNotBefore) {}
}
