package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import java.util.List;
import java.util.Objects;

public record RiotMatchList(List<String> matchIds, ProviderDocument source) {
    public RiotMatchList {
        matchIds = List.copyOf(matchIds);
        source = Objects.requireNonNull(source, "source");
    }
}
