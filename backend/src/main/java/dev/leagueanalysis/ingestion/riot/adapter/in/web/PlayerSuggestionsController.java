package dev.leagueanalysis.ingestion.riot.adapter.in.web;

import dev.leagueanalysis.ingestion.riot.adapter.out.persistence.JdbcRiotIngestionStore;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/player-suggestions")
public class PlayerSuggestionsController {
    private final JdbcRiotIngestionStore store;
    public PlayerSuggestionsController(JdbcRiotIngestionStore store) { this.store = store; }

    @GetMapping
    public ResponseEntity<Result> suggestions(@RequestParam(defaultValue = "NA1") String platform,
            @RequestParam(defaultValue = "") String q) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new Result(store.suggestions(platform, q)));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result> invalid() { return ResponseEntity.badRequest().header("Cache-Control", "no-store").body(new Result(List.of())); }
    public record Result(List<JdbcRiotIngestionStore.PlayerSuggestion> suggestions) {}
}
