package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.List;
import java.util.Objects;

public record ChampionTimingLens(
        String knowledgeVersion,
        List<Capability> capabilities,
        List<EvidenceClaim> claims) implements LensPayload {
    public ChampionTimingLens {
        knowledgeVersion = TimelineEventKey.requireText(knowledgeVersion);
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("CHAMPION_CAPABILITY_REQUIRED");
        }
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
    }

    @Override
    public LensKind kind() {
        return LensKind.CHAMPION_TIMING;
    }

    public record Capability(String category, String statement, CapabilityAssertion assertion) {
        public Capability {
            category = TimelineEventKey.requireText(category);
            statement = TimelineEventKey.requireText(statement);
            assertion = Objects.requireNonNull(assertion, "assertion");
        }
    }
}
