package dev.leagueanalysis.analysis.rank;

import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/matches")
public final class CurrentRankController {
    private final CurrentRankService service;
    public CurrentRankController(CurrentRankService service) { this.service=service; }
    @GetMapping("/{matchId}/ranks")
    public ResponseEntity<CurrentRankService.Result> ranks(@PathVariable String matchId) {
        return service.load(matchId).map(result -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
