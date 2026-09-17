package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.RiotId;

public interface RiotGateway {
    default RiotGateway forPlatform(String platform) { return this; }

    default RiotGateway forPublicLookup() { return this; }

    RiotAccountLookup resolveAccount(RiotId riotId);

    default dev.leagueanalysis.ingestion.riot.domain.PlatformAccountProfile verifyPlatformAccount(String puuid) { return null; }

    RiotMatchList listRankedMatchIds(String puuid, int count);

    default RiotMatchList listMatchIds(String puuid, int queueId, int start, int count, Long endTime) {
        return listRankedMatchIds(puuid, count);
    }

    ProviderDocument fetchMatchDetail(String matchId);

    ProviderDocument fetchMatchTimeline(String matchId);
}
