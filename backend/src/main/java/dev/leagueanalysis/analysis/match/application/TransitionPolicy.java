package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.evidence.domain.TimelineEventKey;

public record TransitionPolicy(
        String version,
        long candidateWindowMs,
        int minimumAbsoluteLeadDelta,
        int maximumSelected) {
    public TransitionPolicy {
        version = TimelineEventKey.requireText(version);
        if (candidateWindowMs <= 0 || minimumAbsoluteLeadDelta < 0 || maximumSelected <= 0) {
            throw new IllegalArgumentException("INVALID_TRANSITION_POLICY");
        }
    }

    public static TransitionPolicy p3() {
        // Prototype thresholds are versioned evaluation hypotheses, not domain truth.
        return new TransitionPolicy("material-transition-p3-v1", 120_000, 800, 5);
    }
}
