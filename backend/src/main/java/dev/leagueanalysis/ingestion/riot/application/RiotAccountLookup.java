package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.RiotAccount;
import java.util.Objects;

public record RiotAccountLookup(RiotAccount account, ProviderDocument source) {
    public RiotAccountLookup {
        account = Objects.requireNonNull(account, "account");
        source = Objects.requireNonNull(source, "source");
    }
}
