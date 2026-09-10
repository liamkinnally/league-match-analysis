package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.QuestionKind;

public record ChampionKnowledgeQuery(
        String gameVersion, int championId, Integer observedItemId,
        long ownedAtMs, QuestionKind questionKind) {}
