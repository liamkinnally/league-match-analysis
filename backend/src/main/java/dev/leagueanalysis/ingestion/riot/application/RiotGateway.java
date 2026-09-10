package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.RiotId;

public interface RiotGateway {
    default RiotGateway forPublicLookup() { return this; }

    RiotAccountLookup resolveAccount(RiotId riotId);

    RiotMatchList listRankedMatchIds(String puuid, int count);

    ProviderDocument fetchMatchDetail(String matchId);

    ProviderDocument fetchMatchTimeline(String matchId);
}
