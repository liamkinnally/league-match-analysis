package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.CapabilityAssertion;
import java.util.List;
import java.util.Optional;

public interface ChampionKnowledgePort {
    List<CapabilityAssertion> findApplicable(ChampionKnowledgeQuery query);

    default Optional<String> knowledgeVersion() {
        return Optional.empty();
    }
}
