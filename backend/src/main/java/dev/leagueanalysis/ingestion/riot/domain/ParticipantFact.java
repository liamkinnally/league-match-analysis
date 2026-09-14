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
        List<Integer> endItemIds, ParticipantDetails details) {
    public ParticipantFact(
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
        this(matchId, participantId, puuid, gameName, tagLine, teamId, championId, championName, teamPosition, kills, deaths, assists, totalMinionsKilled, neutralMinionsKilled, goldEarned, goldSpent, visionScore, summonerSpellOneId, summonerSpellTwoId, win, endItemIds, ParticipantDetails.empty());
    }

    public ParticipantFact {
        details = details == null ? ParticipantDetails.empty() : details;
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
