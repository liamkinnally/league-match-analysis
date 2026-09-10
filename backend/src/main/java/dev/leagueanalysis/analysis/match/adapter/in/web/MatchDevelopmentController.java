package dev.leagueanalysis.analysis.match.adapter.in.web;

import dev.leagueanalysis.analysis.match.application.MatchDevelopmentService;
import dev.leagueanalysis.analysis.match.domain.MatchDevelopment;
import dev.leagueanalysis.demo.DemoSeedCommand;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class MatchDevelopmentController {
    private final MatchDevelopmentService service;
    private final DemoSeedCommand demo;

    public MatchDevelopmentController(MatchDevelopmentService service, DemoSeedCommand demo) {
        this.service = service;
        this.demo = demo;
    }

    @GetMapping("/matches/{matchId}/development")
    public MatchDevelopment development(
            @PathVariable("matchId") String matchId,
            @RequestParam("focus") int focus,
            @RequestParam(value = "compare", required = false) Integer compare,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        return service.load(matchId, focus, compare).orElseThrow(MatchNotFoundException::new);
    }

    @GetMapping("/demo")
    public DemoSeedCommand.DemoMatch demo(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        return demo.locate().orElseThrow(MatchNotFoundException::new);
    }
}
