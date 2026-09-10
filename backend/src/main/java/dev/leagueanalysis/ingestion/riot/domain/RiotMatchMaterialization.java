package dev.leagueanalysis.ingestion.riot.domain;

import java.util.List;
import java.util.Objects;

public record RiotMatchMaterialization(
        MatchFact match,
        List<TeamFact> teams,
        List<ParticipantFact> participants,
        List<ParticipantStateObservation> observations,
        List<MatchEvent> events,
        List<EvidenceCoverage> coverage) {
    public RiotMatchMaterialization {
        match = Objects.requireNonNull(match, "match");
        teams = List.copyOf(teams);
        participants = List.copyOf(participants);
        observations = List.copyOf(observations);
        events = List.copyOf(events);
        coverage = List.copyOf(coverage);
    }
}
