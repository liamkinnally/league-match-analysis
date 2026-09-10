package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.Comparator;

public record MatchParticipant(
        int participantId,
        int teamId,
        int championId,
        String championName,
        String teamPosition,
        boolean win) {
    public static final Comparator<MatchParticipant> STABLE_ORDER = Comparator
            .comparingInt(MatchParticipant::participantId)
            .thenComparingInt(MatchParticipant::teamId)
            .thenComparingInt(MatchParticipant::championId);

    public MatchParticipant {
        championName = TimelineEventKey.requireText(championName);
        teamPosition = TimelineEventKey.requireText(teamPosition);
        if (participantId < 1 || participantId > 10) {
            throw new IllegalArgumentException("INVALID_PARTICIPANT_ID");
        }
        if (teamId < 0 || championId < 0) {
            throw new IllegalArgumentException("INVALID_PARTICIPANT_FACT");
        }
    }
}
