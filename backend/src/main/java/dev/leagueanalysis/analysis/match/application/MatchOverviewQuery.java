package dev.leagueanalysis.analysis.match.application;

import java.util.List;
import java.util.Optional;

public interface MatchOverviewQuery {
    Optional<Overview> load(String matchId);

    record Overview(
            String matchId,
            int queueId,
            int mapId,
            String gameMode,
            String gameVersion,
            long gameCreationMs,
            long durationMs,
            List<Participant> participants,
            List<dev.leagueanalysis.analysis.match.domain.MatchDevelopment.TeamResult> teams,
            List<dev.leagueanalysis.analysis.match.domain.MatchDevelopment.Event> events) {
        public Overview(
            String matchId,
            int queueId,
            int mapId,
            String gameMode,
            String gameVersion,
            long gameCreationMs,
            long durationMs,
            List<Participant> participants) {
            this(matchId, queueId, mapId, gameMode, gameVersion, gameCreationMs, durationMs, participants, List.of(), null);
        }

        public Overview {
            participants = List.copyOf(participants);
        }
    }

    record Participant(
            int participantId,
            int teamId,
            int championId,
            String championName,
            String teamPosition,
            boolean win,
            int kills,
            int deaths,
            int assists,
            int laneCs,
            int jungleCs,
            int goldEarned,
            int goldSpent,
            int visionScore,
            int summonerSpellOneId,
            int summonerSpellTwoId,
            List<Integer> endItemIds,
            String gameName, String tagLine, String summonerName, dev.leagueanalysis.ingestion.riot.domain.ParticipantDetails.Runes runeSnapshot, dev.leagueanalysis.ingestion.riot.domain.ParticipantDetails.Totals participantTotals) {
        public Participant(
            int participantId,
            int teamId,
            int championId,
            String championName,
            String teamPosition,
            boolean win,
            int kills,
            int deaths,
            int assists,
            int laneCs,
            int jungleCs,
            int goldEarned,
            int goldSpent,
            int visionScore,
            int summonerSpellOneId,
            int summonerSpellTwoId,
            List<Integer> endItemIds,
            String gameName, String tagLine, String summonerName) {
            this(participantId, teamId, championId, championName, teamPosition, win, kills, deaths, assists, laneCs, jungleCs, goldEarned, goldSpent, visionScore, summonerSpellOneId, summonerSpellTwoId, endItemIds, gameName, tagLine, summonerName, null, null);
        }

        public Participant(
            int participantId,
            int teamId,
            int championId,
            String championName,
            String teamPosition,
            boolean win,
            int kills,
            int deaths,
            int assists,
            int laneCs,
            int jungleCs,
            int goldEarned,
            int goldSpent,
            int visionScore,
            int summonerSpellOneId,
            int summonerSpellTwoId,
            List<Integer> endItemIds) {
            this(participantId, teamId, championId, championName, teamPosition, win, kills, deaths, assists, laneCs, jungleCs, goldEarned, goldSpent, visionScore, summonerSpellOneId, summonerSpellTwoId, endItemIds, null, null, null);
        }

        public Participant {
            endItemIds = List.copyOf(endItemIds);
        }

        public int totalCs() {
            return Math.addExact(laneCs, jungleCs);
        }
    }
}
