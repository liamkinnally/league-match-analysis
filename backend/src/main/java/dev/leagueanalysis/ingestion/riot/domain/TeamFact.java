package dev.leagueanalysis.ingestion.riot.domain;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

public record TeamFact(String matchId, int teamId, boolean win, JsonNode objectives) {
    public TeamFact {
        matchId = DomainText.require(matchId);
        objectives = Objects.requireNonNull(objectives, "objectives").deepCopy();
    }

    @Override
    public JsonNode objectives() {
        return objectives.deepCopy();
    }
}
