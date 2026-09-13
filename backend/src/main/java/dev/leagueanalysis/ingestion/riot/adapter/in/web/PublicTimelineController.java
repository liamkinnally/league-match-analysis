package dev.leagueanalysis.ingestion.riot.adapter.in.web;

import dev.leagueanalysis.ingestion.riot.application.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/matches/{matchId}/timeline")
public class PublicTimelineController {
    private final PublicMatchLookupService service;
    private final Clock clock;
    public PublicTimelineController(PublicMatchLookupService service, Clock clock) { this.service = service; this.clock = clock; }

    @GetMapping public ResponseEntity<TimelineLookup> get(@PathVariable String matchId) {
        return response(service.timeline(matchId));
    }
    @PostMapping public ResponseEntity<TimelineLookup> request(@PathVariable String matchId, HttpServletRequest request) {
        return response(service.requestTimeline(matchId, request.getRemoteAddr()));
    }
    private ResponseEntity<TimelineLookup> response(TimelineLookup value) {
        return ResponseEntity.status(value.status().equals("RUNNING") ? 202 : 200).header("Cache-Control", "no-store").body(value);
    }
    @ExceptionHandler(PublicLookupException.class)
    public ResponseEntity<PublicMatchLookupController.Error> failure(PublicLookupException error) {
        var response = ResponseEntity.status(error.status()).header("Cache-Control", "no-store");
        if (error.retryNotBefore() != null) response.header("Retry-After", Long.toString(Math.max(1,
                (Duration.between(clock.instant(), error.retryNotBefore()).toMillis() + 999) / 1000)));
        return response.body(new PublicMatchLookupController.Error(error.getMessage(), error.retryNotBefore()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<PublicMatchLookupController.Error> invalid() {
        return ResponseEntity.badRequest().body(new PublicMatchLookupController.Error("Invalid match.", null));
    }
}
