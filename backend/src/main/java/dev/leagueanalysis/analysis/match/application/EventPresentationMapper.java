package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.MatchDevelopment;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Interprets only evidenced actor teams; affected-object ownership has separate availability. */
public final class EventPresentationMapper {
    private EventPresentationMapper() {}
    public static MatchDevelopment.EventPresentation project(String type, Integer actor, JsonNode fields,
            List<MatchOverviewQuery.Participant> roster) {
        Integer team = actor == null || actor <= 0 ? null : roster.stream()
                .filter(p -> p.participantId() == actor).map(MatchOverviewQuery.Participant::teamId).findFirst().orElse(null);
        var actorTeam = new MatchDevelopment.EventTeam(team, team == null ? "missing" : "known");
        // killerTeamId has an actor-team meaning for monster kills; teamId does not share that meaning.
        if (type.equals("ELITE_MONSTER_KILL")) {
            var raw = fields.path("killerTeamId");
            Integer supplied = raw.isIntegralNumber() && raw.canConvertToInt() && (raw.intValue() == 100 || raw.intValue() == 200)
                    ? raw.intValue() : null;
            if (supplied != null) actorTeam = team != null && !team.equals(supplied)
                    ? new MatchDevelopment.EventTeam(null, "conflicting") : new MatchDevelopment.EventTeam(supplied,"known");
        }
        // The destroyed ward/structure owner is not established by the actor's team.
        var objectTeam = new MatchDevelopment.EventTeam(null,
                type.equals("BUILDING_KILL") || type.equals("TURRET_PLATE_DESTROYED") ? "unsupported" : "missing");
        if (type.equals("WARD_PLACED")) objectTeam = actorTeam;
        return new MatchDevelopment.EventPresentation(actorTeam, objectTeam);
    }
}
