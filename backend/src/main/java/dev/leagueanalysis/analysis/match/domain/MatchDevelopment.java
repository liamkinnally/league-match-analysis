package dev.leagueanalysis.analysis.match.domain;

import java.util.List;
import java.util.Objects;

public record MatchDevelopment(
        String matchId,
        Summary summary,
        List<Participant> roster,
        boolean timelineAvailable,
        List<Sample> samples,
        List<WindowSummary> windows,
        List<WindowSummary> suggestedWindows,
        List<Event> events,
        List<TeamResult> teams) {
        public MatchDevelopment(
        String matchId,
        Summary summary,
        List<Participant> roster,
        boolean timelineAvailable,
        List<Sample> samples,
        List<WindowSummary> windows,
        List<WindowSummary> suggestedWindows,
        List<Event> events) {
            this(matchId, summary, roster, timelineAvailable, samples, windows, suggestedWindows, events, List.of());
        }

    public MatchDevelopment {
        matchId = requireText(matchId);
        summary = Objects.requireNonNull(summary, "summary");
        roster = List.copyOf(Objects.requireNonNull(roster, "roster"));
        samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
        windows = List.copyOf(Objects.requireNonNull(windows, "windows"));
        suggestedWindows = List.copyOf(Objects.requireNonNull(suggestedWindows, "suggestedWindows"));
        teams = List.copyOf(teams);
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }

    public record TeamResult(int teamId, Boolean win, Integer kills, Integer deaths,
            Integer assists, Integer goldEarned, java.util.Map<String, Integer> objectives) {}

    public record Summary(
            int queueId,
            int mapId,
            String gameMode,
            String gameVersion,
            long gameCreationMs,
            long durationMs,
            int focusParticipantId,
            Integer compareParticipantId,
            boolean win,
            int kills,
            int deaths,
            int assists,
            int totalCs,
            int goldEarned) {
        public Summary {
            gameMode = requireText(gameMode);
            gameVersion = requireText(gameVersion);
        }
    }

    public record Participant(
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
            int totalCs,
            int goldEarned,
            int goldSpent,
            int visionScore,
            int summonerSpellOneId,
            int summonerSpellTwoId,
            List<Integer> endItemIds,
            String gameName, String tagLine, String summonerName) {
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
            int totalCs,
            int goldEarned,
            int goldSpent,
            int visionScore,
            int summonerSpellOneId,
            int summonerSpellTwoId,
            List<Integer> endItemIds) {
            this(participantId, teamId, championId, championName, teamPosition, win, kills, deaths, assists, laneCs, jungleCs, totalCs, goldEarned, goldSpent, visionScore, summonerSpellOneId, summonerSpellTwoId, endItemIds, null, null, null);
        }

        public Participant {
            championName = requireText(championName);
            teamPosition = requireText(teamPosition);
            endItemIds = List.copyOf(Objects.requireNonNull(endItemIds, "endItemIds"));
        }
    }

    public record Sample(
            long timestampMs,
            Integer goldDifference,
            Integer csDifference,
            Integer xpDifference,
            Integer focalLevel,
            Integer compareLevel,
            Integer focalTotalGold,
            Integer focalCs,
            Integer focalXp) {}

    public record WindowSummary(
            String id,
            long startMs,
            long endMs,
            Sample before,
            Sample after,
            String summary) {
        public WindowSummary {
            id = requireText(id);
            before = Objects.requireNonNull(before, "before");
            after = Objects.requireNonNull(after, "after");
            summary = requireText(summary);
        }
    }

    public record Event(
            long timestampMs,
            String label,
            List<Integer> participantIds,
            Integer itemId,
            Integer actorParticipantId,
            Integer targetParticipantId,
            List<Integer> assisterParticipantIds,
            boolean assistersObserved,
            String type, Long frameAtMs, Integer frameEventIndex,
            java.util.Map<String, Object> fields, Integer x, Integer y) {
        public Event(
            long timestampMs,
            String label,
            List<Integer> participantIds,
            Integer itemId,
            Integer actorParticipantId,
            Integer targetParticipantId,
            List<Integer> assisterParticipantIds,
            boolean assistersObserved) {
            this(timestampMs, label, participantIds, itemId, actorParticipantId, targetParticipantId, assisterParticipantIds, assistersObserved, null, null, null, java.util.Map.of(), null, null);
        }

        public Event {
            label = requireText(label);
            participantIds = List.copyOf(Objects.requireNonNull(participantIds, "participantIds"));
            assisterParticipantIds = List.copyOf(Objects.requireNonNull(
                    assisterParticipantIds, "assisterParticipantIds"));
        }
    }

    private static String requireText(String value) {
        var normalized = Objects.requireNonNull(value, "value").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("BLANK_DEVELOPMENT_TEXT");
        }
        return normalized;
    }
}
