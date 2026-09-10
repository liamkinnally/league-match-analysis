package dev.leagueanalysis.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.match.application.AnalysisRequest;
import dev.leagueanalysis.analysis.match.application.MatchAnalysisService;
import dev.leagueanalysis.analysis.match.domain.LensKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ActiveProfiles("e2e")
class P3E2eFixtureIntegrationTest {
    @Autowired MatchAnalysisService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void browserEvidenceNeverDecreasesParticipantCumulativeGold() {
        var decreases = jdbc.queryForList("""
                select participant_id, represented_at_ms, total_gold, previous_total_gold
                from (
                    select participant_id, represented_at_ms, total_gold,
                           lag(total_gold) over (
                               partition by participant_id order by represented_at_ms
                           ) as previous_total_gold
                    from league_analysis.participant_state_observation
                    where match_id = ?
                ) samples
                where total_gold < previous_total_gold
                order by participant_id, represented_at_ms
                """, P3SanitizedMatchFixture.MATCH_ID);
        assertThat(decreases).as("Participant cumulative gold must not decrease between samples")
                .isEmpty();
    }

    @Test
    void startupProvidesTheFullStoryFromStoredEvidence() {
        var analysis = service.analyze(new AnalysisRequest(P3SanitizedMatchFixture.MATCH_ID,
                6, null, null, null, null, null, null)).orElseThrow();
        assertThat(analysis.arc().transitions()).hasSize(4);
        assertThat(analysis.arc().transitions().getFirst().interval().startMs()).isEqualTo(500_210L);
        var e2 = analysis.arc().transitions().get(1);
        assertThat(e2.receipt().before().orElseThrow().focalTeamLead()).isEqualTo(819);
        assertThat(e2.receipt().after().orElseThrow().focalTeamLead()).isEqualTo(3_142);
        var active = service.analyze(new AnalysisRequest(P3SanitizedMatchFixture.MATCH_ID,
                6, e2.transitionId(), e2.interval().startMs(), e2.interval().endMs(),
                e2.questionKind().questionId(), analysis.evidenceRevision(), LensKind.CHAMPION_TIMING))
                .orElseThrow().active().orElseThrow();
        assertThat(active.context().selectedLens()).isEqualTo(LensKind.CHAMPION_TIMING);
    }
}
