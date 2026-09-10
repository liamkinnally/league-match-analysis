package dev.leagueanalysis.ingestion.riot.domain;

import java.util.List;

public record ParticipantFact(
        String matchId,
        int participantId,
        String puuid,
        String gameName,
        String tagLine,
        int teamId,
        int championId,
        String championName,
        String teamPosition,
        int kills,
        int deaths,
        int assists,
        int totalMinionsKilled,
        int neutralMinionsKilled,
        int goldEarned,
        int goldSpent,
        int visionScore,
        int summonerSpellOneId,
        int summonerSpellTwoId,
        boolean win,
        List<Integer> endItemIds) {
    public ParticipantFact {
        matchId = DomainText.require(matchId);
        puuid = DomainText.require(puuid);
        championName = DomainText.require(championName);
        teamPosition = DomainText.require(teamPosition);
        if (endItemIds == null
                || endItemIds.size() != 7
                || endItemIds.stream().anyMatch(itemId -> itemId == null || itemId < 0)) {
            throw new IllegalArgumentException("INVALID_END_ITEM_SLOTS");
        }
        endItemIds = List.copyOf(endItemIds);
    }
}
