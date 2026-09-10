package dev.leagueanalysis.analysis.rank;

import java.util.List;
import java.util.Optional;

public interface RankRosterQuery {
    Optional<Roster> load(String matchId);
    record Roster(String matchId, int queueId, List<Player> players) {}
    // Internal only: this record must never be returned by a controller.
    record Player(int participantId, String puuid) {}
}
