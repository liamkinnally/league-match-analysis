package dev.leagueanalysis.analysis.profile;

import dev.leagueanalysis.ingestion.riot.application.PublicMatchLookupService;
import dev.leagueanalysis.ingestion.riot.application.PublicLookupException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/player-matches")
public class PlayerProfileController {
    private final PlayerProfileService profiles;
    private final PublicMatchLookupService lookup;
    private final Clock clock;
    public PlayerProfileController(PlayerProfileService profiles,PublicMatchLookupService lookup,Clock clock){this.profiles=profiles;this.lookup=lookup;this.clock=clock;}
    @GetMapping("/{runId}/profile")
    public ResponseEntity<PlayerProfile> get(@PathVariable UUID runId,@RequestParam(required=false) String cursor){
        return ResponseEntity.ok().header("Cache-Control","no-store").body(profiles.load(runId,cursor));
    }
    @PostMapping("/{runId}/recent-record")
    public ResponseEntity<PlayerProfile> recent(@PathVariable UUID runId,HttpServletRequest request){
        profiles.load(runId,null); // Verify the bound stored identity before scheduling anything.
        var result=lookup.recentRecord(runId,request.getRemoteAddr());
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control","no-store").body(profiles.load(runId,null));
    }
    @ExceptionHandler(PublicLookupException.class)
    public ResponseEntity<Error> failure(PublicLookupException failure){
        var response=ResponseEntity.status(failure.status()).header("Cache-Control","no-store");
        if(failure.retryNotBefore()!=null)response.header("Retry-After",Long.toString(Math.max(1,Duration.between(clock.instant(),failure.retryNotBefore()).toSeconds())));
        return response.body(new Error(failure.getMessage(),failure.retryNotBefore()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Error> invalid(){return ResponseEntity.badRequest().header("Cache-Control","no-store").body(new Error("Invalid profile or observation cursor.",null));}
    public record Error(String message,Instant retryNotBefore){}
}
