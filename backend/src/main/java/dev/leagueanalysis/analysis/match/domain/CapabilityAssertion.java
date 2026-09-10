package dev.leagueanalysis.analysis.match.domain;

import java.util.List;

public record CapabilityAssertion(
        String assertionId, String patch, String applicableBuild,
        EntityType entityType, int entityId,
        List<CapabilityTag> capabilities, List<Prerequisite> prerequisites,
        String summary, String sourceUri, String sourceRevision,
        String reviewerId, ReviewStatus reviewStatus, int reviewRevision,
        List<Prerequisite> missingPrerequisites) {
    public CapabilityAssertion {
        capabilities = List.copyOf(capabilities);
        prerequisites = List.copyOf(prerequisites);
        missingPrerequisites = List.copyOf(missingPrerequisites);
    }

    public AssertionMode assertionMode() {
        return AssertionMode.EXPERT_MAINTAINED;
    }

    public enum EntityType { ITEM }
    public enum CapabilityTag { SLOW, MOVEMENT_SPEED }
    public enum Prerequisite { OWNED, ACTIVE_USE_REQUIRED, CHAMPION_HIT_FOR_MOVEMENT }
}
