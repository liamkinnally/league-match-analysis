package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AnalysisRevision(
        String evidenceRevision,
        List<String> historicalMethodVersions,
        String transitionPolicyVersion,
        String receiptProjectionVersion,
        String lensPolicyVersion,
        String episodePolicyVersion,
        String reviewOrderingPolicyVersion,
        Optional<String> championKnowledgeVersion) {
    public AnalysisRevision {
        evidenceRevision = TimelineEventKey.requireText(evidenceRevision);
        if (!evidenceRevision.matches("ev_[0-9a-f]{64}")) {
            throw new IllegalArgumentException("INVALID_EVIDENCE_REVISION");
        }
        historicalMethodVersions = Objects.requireNonNull(
                        historicalMethodVersions, "historicalMethodVersions")
                .stream()
                .map(TimelineEventKey::requireText)
                .distinct()
                .sorted()
                .toList();
        transitionPolicyVersion = TimelineEventKey.requireText(transitionPolicyVersion);
        receiptProjectionVersion = TimelineEventKey.requireText(receiptProjectionVersion);
        lensPolicyVersion = TimelineEventKey.requireText(lensPolicyVersion);
        episodePolicyVersion = TimelineEventKey.requireText(episodePolicyVersion);
        reviewOrderingPolicyVersion = TimelineEventKey.requireText(
                reviewOrderingPolicyVersion);
        championKnowledgeVersion = Objects.requireNonNull(
                        championKnowledgeVersion, "championKnowledgeVersion")
                .map(TimelineEventKey::requireText);
    }
}
