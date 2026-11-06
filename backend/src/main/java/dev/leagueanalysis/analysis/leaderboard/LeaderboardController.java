package dev.leagueanalysis.analysis.leaderboard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/leaderboard")
public class LeaderboardController {
    private final LeaderboardService service;
    public LeaderboardController(LeaderboardService service){this.service=service;}
    @GetMapping("/na") public ResponseEntity<Leaderboard> get(){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.read());}
}
