package dev.leagueanalysis.ingestion.riot.adapter.in.web;

import dev.leagueanalysis.ingestion.riot.application.RiotIngestionCommand;
import dev.leagueanalysis.ingestion.riot.application.RiotIngestionService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/local/riot/ingestions")
public class RiotIngestionController {
    private final RiotIngestionService service;

    public RiotIngestionController(RiotIngestionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RiotIngestionResponse start(@Valid @RequestBody StartRiotIngestionRequest request) {
        var command = new RiotIngestionCommand(
                request.gameName(), request.tagLine(), request.matchLimit());
        return RiotIngestionResponse.from(service.ingest(command));
    }
}
