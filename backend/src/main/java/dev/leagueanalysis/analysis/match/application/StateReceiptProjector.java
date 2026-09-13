package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.AssertionMode;
import dev.leagueanalysis.analysis.match.domain.EvidenceClaim;
import dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot;
import dev.leagueanalysis.analysis.match.domain.MatchParticipant;
import dev.leagueanalysis.analysis.match.domain.ParticipantObservation;
import dev.leagueanalysis.analysis.match.domain.ParticipantReceipt;
import dev.leagueanalysis.analysis.match.domain.StateReceipt;
import dev.leagueanalysis.analysis.match.domain.TimeInterval;
import dev.leagueanalysis.evidence.domain.EvidenceReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public final class StateReceiptProjector {
    public static final String PROJECTION_RULE_VERSION = "state-receipt-p3-v1";

    public StateReceipt project(
            MatchEvidenceSnapshot snapshot, TimeInterval interval, int focalParticipantId) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(interval, "interval");
        var focal = snapshot.participants().stream()
                .filter(participant -> participant.participantId() == focalParticipantId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "FOCAL_PARTICIPANT_NOT_FOUND"));
        var laneOpponentId = snapshot.header().mapId() == 11
                ? laneOpponentId(snapshot.participants(), focal) : Optional.<Integer>empty();
        var limitations = new TreeSet<String>();
        var claims = new ArrayList<EvidenceClaim>();

        var before = representedTimeAtOrBefore(snapshot.observations(), interval.startMs())
                .map(time -> projectSample(
                        snapshot, focal, laneOpponentId, time, false, limitations));
        if (before.isEmpty()) {
            limitations.add("BEFORE_STATE_UNAVAILABLE");
            claims.add(unknownClaim(
                    "claim_receipt_before_unavailable",
                    "A sampled antecedent state is unavailable.",
                    "BEFORE_STATE_UNAVAILABLE"));
        } else {
            claims.add(observedSampleClaim(before.orElseThrow(), "before"));
        }

        var after = representedTimeAtOrAfter(snapshot.observations(), interval.endMs())
                .map(time -> projectSample(
                        snapshot, focal, laneOpponentId, time, true, limitations));
        if (after.isEmpty()) {
            limitations.add("AFTER_STATE_UNAVAILABLE");
            claims.add(unknownClaim(
                    "claim_receipt_after_unavailable",
                    "A sampled consequence state is unavailable.",
                    "AFTER_STATE_UNAVAILABLE"));
        } else {
            claims.add(observedSampleClaim(after.orElseThrow(), "after"));
        }

        var leadDelta = before.flatMap(sample -> Optional.ofNullable(sample.focalTeamLead()))
                .flatMap(beforeLead -> after
                        .flatMap(sample -> Optional.ofNullable(sample.focalTeamLead()))
                        .map(afterLead -> afterLead - beforeLead))
                .orElse(null);
        var evidence = new ArrayList<EvidenceReference>();
        before.ifPresent(sample -> evidence.addAll(sample.evidenceReferences()));
        after.ifPresent(sample -> evidence.addAll(sample.evidenceReferences()));
        return new StateReceipt(
                interval,
                before,
                after,
                leadDelta,
                PROJECTION_RULE_VERSION,
                evidence,
                claims,
                limitations);
    }

    private StateReceipt.Sample projectSample(
            MatchEvidenceSnapshot snapshot,
            MatchParticipant focal,
            Optional<Integer> laneOpponentId,
            long representedAtMs,
            boolean consequenceEvidence,
            Set<String> receiptLimitations) {
        var observations = snapshot.observations().stream()
                .filter(observation -> observation.representedAtMs() == representedAtMs)
                .toList();
        var grouped = new HashMap<Integer, List<ParticipantObservation>>();
        observations.forEach(observation -> grouped
                .computeIfAbsent(observation.participantId(), ignored -> new ArrayList<>())
                .add(observation));
        var unique = new HashMap<Integer, ParticipantObservation>();
        var sampleLimitations = new TreeSet<String>();
        grouped.forEach((participantId, reports) -> {
            if (reports.size() == 1) {
                unique.put(participantId, reports.getFirst());
            } else {
                sampleLimitations.add("AMBIGUOUS_PARTICIPANT_SAMPLE");
            }
        });

        var focalTeamTotal = teamTotal(
                snapshot.participants(), unique, focal.teamId(),
                "FOCAL_TEAM_TOTAL_UNAVAILABLE", sampleLimitations);
        var opponentTeamIds = snapshot.participants().stream()
                .map(MatchParticipant::teamId)
                .filter(teamId -> teamId != focal.teamId())
                .distinct()
                .toList();
        Integer opponentTeamTotal = null;
        if (opponentTeamIds.size() == 1) {
            opponentTeamTotal = teamTotal(
                    snapshot.participants(), unique, opponentTeamIds.getFirst(),
                    "OPPONENT_TEAM_TOTAL_UNAVAILABLE", sampleLimitations);
        } else {
            sampleLimitations.add("OPPONENT_TEAM_UNAVAILABLE");
        }
        var focalLead = focalTeamTotal == null || opponentTeamTotal == null
                ? null
                : focalTeamTotal - opponentTeamTotal;

        var focalReceipt = Optional.ofNullable(unique.get(focal.participantId()))
                .map(this::participantReceipt);
        if (focalReceipt.isEmpty()) {
            sampleLimitations.add("FOCAL_PARTICIPANT_STATE_UNAVAILABLE");
        }
        var laneReceipt = laneOpponentId
                .flatMap(id -> Optional.ofNullable(unique.get(id)))
                .map(this::participantReceipt);
        if (laneOpponentId.isEmpty() || laneReceipt.isEmpty()) {
            sampleLimitations.add("LANE_OPPONENT_STATE_UNAVAILABLE");
        }
        receiptLimitations.addAll(sampleLimitations);
        return new StateReceipt.Sample(
                representedAtMs,
                focalTeamTotal,
                opponentTeamTotal,
                focalLead,
                focalReceipt,
                laneReceipt,
                observations.stream().map(ParticipantObservation::evidence).toList(),
                consequenceEvidence,
                sampleLimitations);
    }

    private Integer teamTotal(
            List<MatchParticipant> participants,
            HashMap<Integer, ParticipantObservation> observations,
            int teamId,
            String limitation,
            Set<String> limitations) {
        var team = participants.stream()
                .filter(participant -> participant.teamId() == teamId)
                .toList();
        if (team.isEmpty() || team.stream()
                .anyMatch(participant -> !observations.containsKey(participant.participantId()))) {
            limitations.add(limitation);
            return null;
        }
        return team.stream()
                .mapToInt(participant -> observations.get(participant.participantId()).totalGold())
                .sum();
    }

    private ParticipantReceipt participantReceipt(ParticipantObservation observation) {
        return new ParticipantReceipt(
                observation.participantId(),
                observation.currentGold(),
                observation.totalGold(),
                observation.level(),
                observation.minionsKilled() + observation.jungleMinionsKilled(),
                observation.evidence());
    }

    private Optional<Integer> laneOpponentId(
            List<MatchParticipant> participants, MatchParticipant focal) {
        var candidates = participants.stream()
                .filter(participant -> participant.teamId() != focal.teamId())
                .filter(participant -> participant.teamPosition().equals(focal.teamPosition()))
                .map(MatchParticipant::participantId)
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private Optional<Long> representedTimeAtOrBefore(
            List<ParticipantObservation> observations, long boundaryMs) {
        return observations.stream()
                .map(ParticipantObservation::representedAtMs)
                .filter(time -> time <= boundaryMs)
                .max(Long::compareTo);
    }

    private Optional<Long> representedTimeAtOrAfter(
            List<ParticipantObservation> observations, long boundaryMs) {
        return observations.stream()
                .map(ParticipantObservation::representedAtMs)
                .filter(time -> time >= boundaryMs)
                .min(Long::compareTo);
    }

    private EvidenceClaim observedSampleClaim(StateReceipt.Sample sample, String role) {
        return new EvidenceClaim(
                "claim_receipt_" + role + "_" + sample.representedAtMs(),
                (sample.consequenceEvidence() ? "Consequence" : "Antecedent")
                        + " team economy was sampled at "
                        + sample.representedAtMs() + " ms.",
                AssertionMode.OBSERVED,
                sample.evidenceReferences(),
                sample.limitationCodes());
    }

    private EvidenceClaim unknownClaim(String id, String statement, String limitation) {
        return new EvidenceClaim(
                id, statement, AssertionMode.UNKNOWN, List.of(), Set.of(limitation));
    }
}
