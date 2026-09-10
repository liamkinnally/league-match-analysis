package dev.leagueanalysis.analysis.death.domain;

import java.util.Objects;

public record ObjectiveRelationship(
        ObjectiveEvent objective,
        TemporalRelation temporalRelation,
        long signedDeltaMs) {
    public ObjectiveRelationship {
        objective = Objects.requireNonNull(objective, "objective");
        temporalRelation = Objects.requireNonNull(temporalRelation, "temporalRelation");
        var expected = signedDeltaMs < 0
                ? TemporalRelation.BEFORE
                : signedDeltaMs == 0 ? TemporalRelation.SAME_TIME : TemporalRelation.AFTER;
        if (temporalRelation != expected) {
            throw new IllegalArgumentException("INCONSISTENT_TEMPORAL_RELATION");
        }
    }
}
