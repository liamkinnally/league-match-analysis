package dev.leagueanalysis.analysis.death.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record DeathEvent(
        TimelineEventKey key,
        Integer victimParticipantId,
        Integer killerParticipantId,
        List<Integer> assistingParticipantIds,
        boolean assistingParticipantIdsObserved,
        Integer positionX,
        Integer positionY,
        List<EvidenceReference> evidenceReferences,
        Set<String> limitationCodes) {
    public DeathEvent {
        key = Objects.requireNonNull(key, "key");
        if (victimParticipantId != null && victimParticipantId < 1) {
            throw new IllegalArgumentException("INVALID_VICTIM_PARTICIPANT_ID");
        }
        if (killerParticipantId != null && killerParticipantId < 0) {
            throw new IllegalArgumentException("INVALID_KILLER_PARTICIPANT_ID");
        }
        assistingParticipantIds = List.copyOf(
                Objects.requireNonNull(assistingParticipantIds, "assistingParticipantIds"));
        if (!assistingParticipantIdsObserved && !assistingParticipantIds.isEmpty()) {
            throw new IllegalArgumentException("UNOBSERVED_ASSISTING_PARTICIPANT_IDS");
        }
        evidenceReferences = Objects.requireNonNull(evidenceReferences, "evidenceReferences").stream()
                .map(reference -> Objects.requireNonNull(reference, "evidenceReference"))
                .distinct()
                .sorted(Comparator.comparingLong(EvidenceReference::representedAtMs)
                        .thenComparing(EvidenceReference::sourceCaptureId)
                        .thenComparing(EvidenceReference::sourceRecordId)
                        .thenComparing(EvidenceReference::methodVersion))
                .toList();
        if (evidenceReferences.isEmpty()) {
            throw new IllegalArgumentException("DEATH_EVIDENCE_REQUIRED");
        }
        var limitations = new TreeSet<>(Objects.requireNonNull(limitationCodes, "limitationCodes"));
        if (limitations.stream().anyMatch(code -> code == null || code.isBlank())) {
            throw new IllegalArgumentException("INVALID_DEATH_LIMITATION");
        }
        if (victimParticipantId == null) {
            limitations.add("VICTIM_PARTICIPANT_ID_UNAVAILABLE");
        }
        limitationCodes = Collections.unmodifiableSortedSet(limitations);
    }

    public DeathEvent(
            TimelineEventKey key,
            int victimParticipantId,
            Integer killerParticipantId,
            List<Integer> assistingParticipantIds,
            Integer positionX,
            Integer positionY,
            EvidenceReference evidence) {
        this(
                key,
                victimParticipantId,
                killerParticipantId,
                assistingParticipantIds,
                true,
                positionX,
                positionY,
                List.of(evidence),
                Set.of());
    }

    public DeathEvent(
            TimelineEventKey key,
            Integer victimParticipantId,
            Integer killerParticipantId,
            List<Integer> assistingParticipantIds,
            boolean assistingParticipantIdsObserved,
            Integer positionX,
            Integer positionY,
            EvidenceReference evidence) {
        this(
                key,
                victimParticipantId,
                killerParticipantId,
                assistingParticipantIds,
                assistingParticipantIdsObserved,
                positionX,
                positionY,
                List.of(evidence),
                Set.of());
    }

    public EvidenceReference evidence() {
        return evidenceReferences.getFirst();
    }

    public static DeathEvent reconcile(List<DeathEvent> reports) {
        var requiredReports = Objects.requireNonNull(reports, "reports").stream()
                .map(report -> Objects.requireNonNull(report, "report"))
                .toList();
        if (requiredReports.isEmpty()) {
            throw new IllegalArgumentException("DEATH_REPORT_REQUIRED");
        }
        var key = requiredReports.getFirst().key();
        if (requiredReports.stream().anyMatch(report -> !report.key().equals(key))) {
            throw new IllegalArgumentException("MISMATCHED_DEATH_EVENT_KEY");
        }
        var evidenceReferences = requiredReports.stream()
                .flatMap(report -> report.evidenceReferences().stream())
                .toList();
        var limitations = new TreeSet<String>();
        requiredReports.forEach(report -> limitations.addAll(report.limitationCodes()));
        var content = DeathContent.from(requiredReports.getFirst());
        if (requiredReports.stream().map(DeathContent::from).allMatch(content::equals)) {
            return new DeathEvent(
                    key,
                    content.victimParticipantId(),
                    content.killerParticipantId(),
                    content.assistingParticipantIds(),
                    content.assistingParticipantIdsObserved(),
                    content.positionX(),
                    content.positionY(),
                    evidenceReferences,
                    limitations);
        }
        limitations.add("AMBIGUOUS_DEATH_EVENT");
        return new DeathEvent(
                key,
                null,
                null,
                List.of(),
                false,
                null,
                null,
                evidenceReferences,
                limitations);
    }

    private record DeathContent(
            Integer victimParticipantId,
            Integer killerParticipantId,
            List<Integer> assistingParticipantIds,
            boolean assistingParticipantIdsObserved,
            Integer positionX,
            Integer positionY) {
        private static DeathContent from(DeathEvent death) {
            return new DeathContent(
                    death.victimParticipantId(),
                    death.killerParticipantId(),
                    death.assistingParticipantIds(),
                    death.assistingParticipantIdsObserved(),
                    death.positionX(),
                    death.positionY());
        }
    }
}
