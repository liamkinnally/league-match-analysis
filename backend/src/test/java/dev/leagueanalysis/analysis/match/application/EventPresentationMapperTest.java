package dev.leagueanalysis.analysis.match.application;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class EventPresentationMapperTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<MatchOverviewQuery.Participant> roster = List.of(
        new MatchOverviewQuery.Participant(1,100,86,"Garen","TOP",true,0,0,0,0,0,0,0,0,4,12,List.of()),
        new MatchOverviewQuery.Participant(6,200,122,"Darius","TOP",false,0,0,0,0,0,0,0,0,4,12,List.of()));
    @Test void separatesActorFromUnverifiedDestroyedObjectOwnership() {
        var event = EventPresentationMapper.project("BUILDING_KILL", 6, json.readTree("{\"teamId\":100}"), roster);
        assertThat(event.actorTeam().teamId()).isEqualTo(200);
        assertThat(event.objectTeam().teamId()).isNull();
        assertThat(event.objectTeam().basis()).isEqualTo("unsupported");
        var ward = EventPresentationMapper.project("WARD_KILL", 1, json.readTree("{}"), roster);
        assertThat(ward.actorTeam().teamId()).isEqualTo(100);
        assertThat(ward.objectTeam().basis()).isEqualTo("missing");
        var placed = EventPresentationMapper.project("WARD_PLACED", 6, json.readTree("{}"), roster);
        assertThat(placed.objectTeam()).isEqualTo(placed.actorTeam());
    }
    @Test void conflictsRemainUnknownAndEnvironmentalKillersAreNotInvented() {
        var conflict = EventPresentationMapper.project("ELITE_MONSTER_KILL", 6, json.readTree("{\"killerTeamId\":100}"), roster);
        assertThat(conflict.actorTeam().basis()).isEqualTo("conflicting");
        assertThat(conflict.actorTeam().teamId()).isNull();
        var environmental = EventPresentationMapper.project("BUILDING_KILL", 0, json.readTree("{}"), roster);
        assertThat(environmental.actorTeam().basis()).isEqualTo("missing");
        var supplied = EventPresentationMapper.project("ELITE_MONSTER_KILL", 0, json.readTree("{\"killerTeamId\":200}"), roster);
        assertThat(supplied.actorTeam().teamId()).isEqualTo(200);
    }
}
