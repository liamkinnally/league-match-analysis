package dev.leagueanalysis.analysis.rank;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Repository
public class JdbcRankRosterQuery implements RankRosterQuery {
    private final JdbcTemplate jdbc;
    public JdbcRankRosterQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<Roster> load(String matchId) {
        var queues = jdbc.queryForList("select queue_id from league_analysis.riot_match where match_id=?", Integer.class, matchId);
        if (queues.isEmpty()) return Optional.empty();
        var players = jdbc.query("select participant_id, puuid from league_analysis.riot_participant where match_id=? order by participant_id",
                (rs, i) -> new Player(rs.getInt("participant_id"), rs.getString("puuid")), matchId);
        return Optional.of(new Roster(matchId, queues.getFirst(), players));
    }
}
