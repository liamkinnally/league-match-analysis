package dev.leagueanalysis.analysis.match.adapter.in.web;

import dev.leagueanalysis.analysis.match.application.AnalysisRequest;
import dev.leagueanalysis.analysis.match.application.MatchAnalysisService;
import dev.leagueanalysis.analysis.match.domain.LensKind;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/matches/{matchId}/analysis")
public final class MatchAnalysisController {
    private final MatchAnalysisService service;

    public MatchAnalysisController(MatchAnalysisService service) {
        this.service = service;
    }

    @GetMapping
    public MatchAnalysisResponse analyze(
            @PathVariable("matchId") String matchId,
            @RequestParam("focalParticipantId") int focalParticipantId,
            @RequestParam(value = "objectId", required = false) String selectedObjectId,
            @RequestParam(value = "intervalStartMs", required = false) Long intervalStartMs,
            @RequestParam(value = "intervalEndMs", required = false) Long intervalEndMs,
            @RequestParam(value = "questionId", required = false) String questionId,
            @RequestParam(value = "evidenceRevision", required = false)
                    String requestedEvidenceRevision,
            @RequestParam(value = "requestedLens", required = false) LensKind requestedLens,
            HttpServletResponse response) {
        response.setHeader(
                HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        var request = new AnalysisRequest(
                matchId,
                focalParticipantId,
                selectedObjectId,
                intervalStartMs,
                intervalEndMs,
                questionId,
                requestedEvidenceRevision,
                requestedLens);
        return service.analyze(request)
                .map(MatchAnalysisResponseMapper::from)
                .orElseThrow(MatchNotFoundException::new);
    }
}
