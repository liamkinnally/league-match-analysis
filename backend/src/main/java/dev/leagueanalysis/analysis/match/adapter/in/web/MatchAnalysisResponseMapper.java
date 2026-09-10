package dev.leagueanalysis.analysis.match.adapter.in.web;

import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.ActiveResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.AnalysisContextResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.AnalysisRevisionResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.AnchorResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.ArcResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.ChampionCapabilityResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.CapabilityAssertionResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.ChampionTimingLensResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.ClaimResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.ContextualQuestionResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.EpisodeResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.EvidenceReferenceResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.EvidenceResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.FocalParticipantResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.IntervalResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.LensResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.MapLensResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.MapPointResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.MatchResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.ParticipantReceiptResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.ReceiptLensResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.ReceiptResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.ReviewBeatResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.ReviewResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.SampleResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.SequenceBandResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.SequenceLensResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.SequenceRelationResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.SourceCoverageResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.StateLensResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.TransferLensResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.TransferRowResponse;
import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponse.TransitionResponse;
import dev.leagueanalysis.analysis.match.domain.AnalysisRevision;
import dev.leagueanalysis.analysis.match.domain.ChampionTimingLens;
import dev.leagueanalysis.analysis.match.domain.DecisionEpisode;
import dev.leagueanalysis.analysis.match.domain.EvidenceClaim;
import dev.leagueanalysis.analysis.match.domain.LensPayload;
import dev.leagueanalysis.analysis.match.domain.MapLens;
import dev.leagueanalysis.analysis.match.domain.MatchAnalysis;
import dev.leagueanalysis.analysis.match.domain.MatchAnchor;
import dev.leagueanalysis.analysis.match.domain.ParticipantReceipt;
import dev.leagueanalysis.analysis.match.domain.ReceiptLens;
import dev.leagueanalysis.analysis.match.domain.ReviewBeat;
import dev.leagueanalysis.analysis.match.domain.SequenceLens;
import dev.leagueanalysis.analysis.match.domain.StateLens;
import dev.leagueanalysis.analysis.match.domain.StateReceipt;
import dev.leagueanalysis.analysis.match.domain.TimeInterval;
import dev.leagueanalysis.analysis.match.domain.TransferLens;
import dev.leagueanalysis.analysis.match.domain.TransitionPacket;
import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.SourceCoverage;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class MatchAnalysisResponseMapper {
    private MatchAnalysisResponseMapper() {}

    public static MatchAnalysisResponse from(MatchAnalysis analysis) {
        Objects.requireNonNull(analysis, "analysis");
        var header = analysis.match().header();
        var focal = analysis.match().focalParticipant();
        return new MatchAnalysisResponse(
                new MatchResponse(
                        header.matchId(),
                        header.durationMs(),
                        header.gameVersion(),
                        new FocalParticipantResponse(
                                focal.participantId(),
                                focal.championId(),
                                focal.championName(),
                                focal.teamPosition(),
                                focal.teamId(),
                                focal.win())),
                analysis.evidenceRevision(),
                new ArcResponse(
                        analysis.arc().transitions().stream()
                                .map(packet -> transition(packet, analysis.arc().primaryLenses()
                                        .get(packet.transitionId())))
                                .toList(),
                        analysis.arc().eligibleUnselectedCount(),
                        analysis.arc().selectionRuleVersion(),
                        analysis.arc().emptyReason().orElse(null)),
                new ReviewResponse(
                        analysis.review().episodes().stream()
                                .map(episode -> episode(episode, List.of()))
                                .toList(),
                        analysis.review().learningOrder(),
                        analysis.review().chronologicalOrder(),
                        analysis.review().temporalCues(),
                        analysis.review().orderingRuleVersion()),
                analysis.active()
                        .map(MatchAnalysisResponseMapper::active)
                        .orElse(null));
    }

    private static TransitionResponse transition(
            TransitionPacket packet, dev.leagueanalysis.analysis.match.domain.LensKind primaryLens) {
        return new TransitionResponse(
                packet.transitionId(),
                interval(packet.interval()),
                packet.anchors().stream().map(MatchAnalysisResponseMapper::anchor).toList(),
                packet.title(),
                packet.questionKind().questionId(),
                primaryLens.name(),
                packet.materialityReasons().stream()
                        .map(Enum::name)
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new)),
                packet.focalRelationships().stream()
                        .map(Enum::name)
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new)),
                packet.interpretationClaim()
                        .map(value -> claim(value, List.of()))
                        .orElse(null),
                packet.limitationCodes());
    }

    private static ActiveResponse active(MatchAnalysis.ActiveAnalysis active) {
        var coverage = active.evidence().coverage();
        var context = active.context();
        return new ActiveResponse(
                new AnalysisContextResponse(
                        context.matchId(),
                        context.focalParticipantId(),
                        context.selectedObjectId(),
                        interval(context.interval()),
                        context.questionId(),
                        context.evidenceRevision(),
                        context.primaryLens().name(),
                        context.selectedLens().name(),
                        context.availableLenses().stream().map(Enum::name).toList()),
                active.sourceTransitionId(),
                receipt(active.receipt(), coverage),
                lens(active.lens(), coverage),
                active.claims().stream().map(value -> claim(value, coverage)).toList(),
                active.limitations(),
                new EvidenceResponse(
                        active.claims().stream().map(EvidenceClaim::claimId).toList(),
                        active.evidence().references().stream()
                                .map(reference -> evidenceReference(reference, coverage))
                                .toList(),
                        coverage.stream().map(MatchAnalysisResponseMapper::coverage).toList(),
                        revision(active.evidence().revision())),
                active.supportedQuestions().stream()
                        .map(question -> new ContextualQuestionResponse(
                                question.id(), question.prompt(), question.answerMode().name()))
                        .toList(),
                active.linkedReviewEpisode()
                        .map(value -> episode(value, coverage))
                        .orElse(null));
    }

    private static EpisodeResponse episode(
            DecisionEpisode episode, List<SourceCoverage> coverage) {
        return new EpisodeResponse(
                episode.id(),
                episode.transitionId(),
                episode.kind().name(),
                interval(episode.window().interval()),
                episode.questionId(),
                episode.selectionRationale(),
                episode.beats().stream().map(beat -> beat(beat, coverage)).toList(),
                episode.compositionRuleVersion());
    }

    private static ReviewBeatResponse beat(
            ReviewBeat beat, List<SourceCoverage> coverage) {
        return new ReviewBeatResponse(
                beat.id(),
                beat.kind().name(),
                beat.title(),
                beat.detail(),
                beat.assertionMode().name(),
                beat.sourceClaimId().orElse(null),
                beat.evidenceReferences().stream()
                        .map(reference -> evidenceReference(reference, coverage))
                        .toList(),
                beat.limitationCodes());
    }

    private static LensResponse lens(
            LensPayload payload, List<SourceCoverage> coverage) {
        if (payload instanceof MapLens value) {
            return new MapLensResponse(
                    value.kind().name(),
                    value.points().stream()
                            .map(point -> new MapPointResponse(
                                    point.representedAtMs(),
                                    point.x(),
                                    point.y(),
                                    point.anchorKind().name(),
                                    point.participantId(),
                                    point.descriptor(),
                                    point.evidenceReferences().stream()
                                            .map(reference -> evidenceReference(
                                                    reference, coverage))
                                            .toList()))
                            .toList(),
                    value.claims().stream().map(claim -> claim(claim, coverage)).toList());
        }
        if (payload instanceof SequenceLens value) {
            return new SequenceLensResponse(
                    value.kind().name(),
                    value.bands().stream()
                            .map(band -> new SequenceBandResponse(
                                    band.id(), band.startMs(), band.endMs(), band.parallel(),
                                    band.anchors().stream()
                                            .map(MatchAnalysisResponseMapper::anchor)
                                            .toList()))
                            .toList(),
                    value.relations().stream()
                            .map(relation -> new SequenceRelationResponse(
                                    relation.fromBandId(),
                                    relation.toBandId(),
                                    relation.kind().name(),
                                    relation.evidenceReferences().stream()
                                            .map(reference -> evidenceReference(
                                                    reference, coverage))
                                            .toList()))
                            .toList(),
                    value.claims().stream().map(claim -> claim(claim, coverage)).toList());
        }
        if (payload instanceof StateLens value) {
            return new StateLensResponse(
                    value.kind().name(),
                    receipt(value.receipt(), coverage),
                    value.claims().stream().map(claim -> claim(claim, coverage)).toList());
        }
        if (payload instanceof ChampionTimingLens value) {
            return new ChampionTimingLensResponse(
                    value.kind().name(),
                    value.knowledgeVersion(),
                    value.capabilities().stream()
                            .map(capability -> new ChampionCapabilityResponse(
                                    capability.category(), capability.statement(),
                                    capabilityAssertion(capability.assertion())))
                            .toList(),
                    value.claims().stream().map(claim -> claim(claim, coverage)).toList());
        }
        if (payload instanceof TransferLens value) {
            return new TransferLensResponse(
                    value.kind().name(),
                    value.rows().stream()
                            .map(row -> new TransferRowResponse(
                                    row.representedAtMs(),
                                    row.category(),
                                    row.observedFor(),
                                    row.evidenceReferences().stream()
                                            .map(reference -> evidenceReference(
                                                    reference, coverage))
                                            .toList()))
                            .toList(),
                    value.claims().stream().map(claim -> claim(claim, coverage)).toList());
        }
        if (payload instanceof ReceiptLens value) {
            return new ReceiptLensResponse(
                    value.kind().name(),
                    receipt(value.receipt(), coverage),
                    value.claims().stream().map(claim -> claim(claim, coverage)).toList());
        }
        throw new IllegalStateException("Unsupported lens payload: " + payload.kind());
    }

    private static CapabilityAssertionResponse capabilityAssertion(
            dev.leagueanalysis.analysis.match.domain.CapabilityAssertion assertion) {
        return new CapabilityAssertionResponse(
                assertion.assertionId(), assertion.patch(), assertion.applicableBuild(),
                assertion.entityType().name(), assertion.entityId(),
                assertion.capabilities().stream().map(Enum::name).toList(),
                assertion.prerequisites().stream().map(Enum::name).toList(),
                assertion.sourceUri(), assertion.sourceRevision(), assertion.reviewerId(),
                assertion.reviewStatus().name(), assertion.reviewRevision(), assertion.assertionMode().name(),
                assertion.missingPrerequisites().stream().map(Enum::name).toList());
    }

    private static ReceiptResponse receipt(
            StateReceipt receipt, List<SourceCoverage> coverage) {
        return new ReceiptResponse(
                interval(receipt.interval()),
                receipt.before().map(value -> sample(value, coverage)).orElse(null),
                receipt.after().map(value -> sample(value, coverage)).orElse(null),
                receipt.focalTeamLeadDelta(),
                receipt.projectionRuleVersion(),
                receipt.claims().stream().map(value -> claim(value, coverage)).toList(),
                receipt.limitationCodes());
    }

    private static SampleResponse sample(
            StateReceipt.Sample sample, List<SourceCoverage> coverage) {
        return new SampleResponse(
                sample.representedAtMs(),
                sample.focalTeamTotalGold(),
                sample.opponentTeamTotalGold(),
                sample.focalTeamLead(),
                sample.focalParticipant()
                        .map(MatchAnalysisResponseMapper::participantReceipt)
                        .orElse(null),
                sample.laneOpponent()
                        .map(MatchAnalysisResponseMapper::participantReceipt)
                        .orElse(null),
                sample.consequenceEvidence(),
                sample.limitationCodes());
    }

    private static ParticipantReceiptResponse participantReceipt(ParticipantReceipt receipt) {
        return new ParticipantReceiptResponse(
                receipt.participantId(),
                receipt.currentGold(),
                receipt.totalGold(),
                receipt.level(),
                receipt.creepScore());
    }

    private static ClaimResponse claim(
            EvidenceClaim claim, List<SourceCoverage> coverage) {
        return new ClaimResponse(
                claim.claimId(),
                claim.statement(),
                claim.assertionMode().name(),
                claim.evidenceReferences().stream()
                        .map(reference -> evidenceReference(reference, coverage))
                        .toList(),
                claim.limitationCodes());
    }

    private static AnchorResponse anchor(MatchAnchor anchor) {
        return new AnchorResponse(
                anchor.key().representedAtMs(),
                anchor.kind().name(),
                anchor.actorParticipantId(),
                anchor.targetParticipantId(),
                anchor.teamId(),
                anchor.positionX(),
                anchor.positionY(),
                anchor.descriptor(),
                anchor.limitationCodes());
    }

    private static IntervalResponse interval(TimeInterval interval) {
        return new IntervalResponse(interval.startMs(), interval.endMs());
    }

    private static EvidenceReferenceResponse evidenceReference(
            EvidenceReference reference, List<SourceCoverage> coverage) {
        return new EvidenceReferenceResponse(
                sourceKind(reference, coverage),
                reference.sourceCaptureId().toString(),
                reference.sourceRecordId().toString(),
                reference.representedAtMs(),
                reference.methodVersion());
    }

    private static String sourceKind(
            EvidenceReference reference, List<SourceCoverage> coverage) {
        return coverage.stream()
                .filter(item -> Objects.equals(
                        item.sourceCaptureId(), reference.sourceCaptureId()))
                .map(SourceCoverage::sourceKind)
                .distinct()
                .sorted()
                .findFirst()
                .orElse("UNKNOWN");
    }

    private static SourceCoverageResponse coverage(SourceCoverage coverage) {
        return new SourceCoverageResponse(
                coverage.signal(),
                coverage.sourceKind(),
                coverage.status().name(),
                coverage.representedStartMs(),
                coverage.representedEndMs(),
                coverage.sourceCaptureId() == null
                        ? null
                        : coverage.sourceCaptureId().toString(),
                coverage.sourceRecordId().toString(),
                coverage.methodVersion());
    }

    private static AnalysisRevisionResponse revision(AnalysisRevision revision) {
        return new AnalysisRevisionResponse(
                revision.evidenceRevision(),
                revision.historicalMethodVersions(),
                revision.transitionPolicyVersion(),
                revision.receiptProjectionVersion(),
                revision.lensPolicyVersion(),
                revision.episodePolicyVersion(),
                revision.reviewOrderingPolicyVersion(),
                revision.championKnowledgeVersion().orElse(null));
    }
}
