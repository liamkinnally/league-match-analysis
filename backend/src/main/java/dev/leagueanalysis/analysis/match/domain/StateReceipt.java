package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public record StateReceipt(
        TimeInterval interval,
        Optional<Sample> before,
        Optional<Sample> after,
        Integer focalTeamLeadDelta,
        String projectionRuleVersion,
        List<EvidenceReference> evidenceReferences,
        List<EvidenceClaim> claims,
        Set<String> limitationCodes) {
    public StateReceipt {
        interval = Objects.requireNonNull(interval, "interval");
        before = Objects.requireNonNull(before, "before");
        after = Objects.requireNonNull(after, "after");
        projectionRuleVersion = dev.leagueanalysis.evidence.domain.TimelineEventKey.requireText(
                projectionRuleVersion);
        evidenceReferences = sortedEvidence(evidenceReferences);
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        var limitations = new TreeSet<>(
                Objects.requireNonNull(limitationCodes, "limitationCodes"));
        if (limitations.stream().anyMatch(code -> code == null || code.isBlank())) {
            throw new IllegalArgumentException("INVALID_RECEIPT_LIMITATION");
        }
        limitationCodes = Collections.unmodifiableSortedSet(limitations);
    }

    public Long beforeRepresentedAtMs() {
        return before.map(Sample::representedAtMs).orElse(null);
    }

    public Long afterRepresentedAtMs() {
        return after.map(Sample::representedAtMs).orElse(null);
    }

    public Integer focalTeamLeadBefore() {
        return before.map(Sample::focalTeamLead).orElse(null);
    }

    public Integer focalTeamLeadAfter() {
        return after.map(Sample::focalTeamLead).orElse(null);
    }

    public boolean hasUsableBrackets() {
        return before.filter(Sample::hasUsableTeamTotals).isPresent()
                && after.filter(Sample::hasUsableTeamTotals).isPresent()
                && focalTeamLeadDelta != null;
    }

    public record Sample(
            long representedAtMs,
            Integer focalTeamTotalGold,
            Integer opponentTeamTotalGold,
            Integer focalTeamLead,
            Optional<ParticipantReceipt> focalParticipant,
            Optional<ParticipantReceipt> laneOpponent,
            List<EvidenceReference> evidenceReferences,
            boolean consequenceEvidence,
            Set<String> limitationCodes) {
        public Sample {
            if (representedAtMs < 0) {
                throw new IllegalArgumentException("NEGATIVE_REPRESENTED_TIME");
            }
            focalParticipant = Objects.requireNonNull(focalParticipant, "focalParticipant");
            laneOpponent = Objects.requireNonNull(laneOpponent, "laneOpponent");
            evidenceReferences = sortedEvidence(evidenceReferences);
            var limitations = new TreeSet<>(
                    Objects.requireNonNull(limitationCodes, "limitationCodes"));
            if (limitations.stream().anyMatch(code -> code == null || code.isBlank())) {
                throw new IllegalArgumentException("INVALID_SAMPLE_LIMITATION");
            }
            limitationCodes = Collections.unmodifiableSortedSet(limitations);
        }

        public boolean hasUsableTeamTotals() {
            return focalTeamTotalGold != null
                    && opponentTeamTotalGold != null
                    && focalTeamLead != null;
        }
    }

    private static List<EvidenceReference> sortedEvidence(List<EvidenceReference> evidence) {
        return Objects.requireNonNull(evidence, "evidenceReferences").stream()
                .map(reference -> Objects.requireNonNull(reference, "evidenceReference"))
                .distinct()
                .sorted(Comparator.comparingLong(EvidenceReference::representedAtMs)
                        .thenComparing(EvidenceReference::sourceCaptureId)
                        .thenComparing(EvidenceReference::sourceRecordId)
                        .thenComparing(EvidenceReference::methodVersion))
                .toList();
    }
}
