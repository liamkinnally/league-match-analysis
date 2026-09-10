package dev.leagueanalysis.analysis.death.application;

public record DeathContextRequest(
        String matchId,
        long deathFrameAtMs,
        int deathFrameEventIndex,
        long maxPriorObservationAgeMs,
        long objectiveLookbackMs,
        long objectiveLookaheadMs) {
    public DeathContextRequest {
        if (matchId == null || matchId.isBlank()) {
            throw new IllegalArgumentException("BLANK_MATCH_ID");
        }
        matchId = matchId.strip();
        if (deathFrameAtMs < 0) {
            throw new IllegalArgumentException("NEGATIVE_DEATH_FRAME_TIME");
        }
        if (deathFrameEventIndex < 0) {
            throw new IllegalArgumentException("NEGATIVE_DEATH_EVENT_INDEX");
        }
        if (maxPriorObservationAgeMs < 0) {
            throw new IllegalArgumentException("NEGATIVE_MAX_PRIOR_OBSERVATION_AGE");
        }
        if (objectiveLookbackMs < 0) {
            throw new IllegalArgumentException("NEGATIVE_OBJECTIVE_LOOKBACK");
        }
        if (objectiveLookaheadMs < 0) {
            throw new IllegalArgumentException("NEGATIVE_OBJECTIVE_LOOKAHEAD");
        }
    }
}
