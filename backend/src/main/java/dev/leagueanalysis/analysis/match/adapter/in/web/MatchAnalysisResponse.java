package dev.leagueanalysis.analysis.match.adapter.in.web;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record MatchAnalysisResponse(
        MatchResponse match,
        String evidenceRevision,
        ArcResponse arc,
        ReviewResponse review,
        ActiveResponse active) {

    public record MatchResponse(
            String matchId,
            long durationMs,
            String gameVersion,
            FocalParticipantResponse focalParticipant) {}

    public record FocalParticipantResponse(
            int participantId,
            int championId,
            String championName,
            String teamPosition,
            int teamId,
            boolean win) {}

    public record ArcResponse(
            List<TransitionResponse> transitions,
            int eligibleUnselectedCount,
            String selectionRuleVersion,
            String emptyReason) {}

    public record TransitionResponse(
            String transitionId,
            IntervalResponse interval,
            List<AnchorResponse> anchors,
            String title,
            String questionId,
            String primaryLens,
            Set<String> materialityReasons,
            Set<String> focalRelationships,
            ClaimResponse interpretation,
            Set<String> limitations) {}

    public record AnchorResponse(
            long representedAtMs,
            String kind,
            Integer actorParticipantId,
            Integer targetParticipantId,
            Integer teamId,
            Integer x,
            Integer y,
            String descriptor,
            Set<String> limitations) {}

    public record IntervalResponse(long startMs, long endMs) {}

    public record ReviewResponse(
            List<EpisodeResponse> episodes,
            List<String> learningOrder,
            List<String> chronologicalOrder,
            Map<String, String> temporalCues,
            String orderingRuleVersion) {}

    public record EpisodeResponse(
            String id,
            String transitionId,
            String kind,
            IntervalResponse interval,
            String questionId,
            String selectionRationale,
            List<ReviewBeatResponse> beats,
            String compositionRuleVersion) {}

    public record ReviewBeatResponse(
            String id,
            String kind,
            String title,
            String detail,
            String assertionMode,
            String sourceClaimId,
            List<EvidenceReferenceResponse> evidenceReferences,
            Set<String> limitations) {}

    public record ActiveResponse(
            AnalysisContextResponse context,
            String sourceTransitionId,
            ReceiptResponse receipt,
            LensResponse lens,
            List<ClaimResponse> claims,
            Set<String> limitations,
            EvidenceResponse evidence,
            List<ContextualQuestionResponse> supportedQuestions,
            EpisodeResponse linkedReviewEpisode) {}

    public record AnalysisContextResponse(
            String matchId,
            int focalParticipantId,
            String selectedObjectId,
            IntervalResponse interval,
            String questionId,
            String evidenceRevision,
            String primaryLens,
            String selectedLens,
            List<String> availableLenses) {}

    public record ReceiptResponse(
            IntervalResponse interval,
            SampleResponse before,
            SampleResponse after,
            Integer focalTeamLeadDelta,
            String projectionRuleVersion,
            List<ClaimResponse> claims,
            Set<String> limitations) {}

    public record SampleResponse(
            long representedAtMs,
            Integer focalTeamTotalGold,
            Integer opponentTeamTotalGold,
            Integer focalTeamLead,
            ParticipantReceiptResponse focalParticipant,
            ParticipantReceiptResponse laneOpponent,
            boolean consequenceEvidence,
            Set<String> limitations) {}

    public record ParticipantReceiptResponse(
            int participantId,
            int currentGold,
            int totalGold,
            int level,
            int creepScore) {}

    public sealed interface LensResponse
            permits MapLensResponse, SequenceLensResponse, StateLensResponse,
                    ChampionTimingLensResponse, TransferLensResponse, ReceiptLensResponse {
        String type();
    }

    public record MapLensResponse(
            String type,
            List<MapPointResponse> points,
            List<ClaimResponse> claims) implements LensResponse {}

    public record MapPointResponse(
            long representedAtMs,
            int x,
            int y,
            String anchorKind,
            Integer participantId,
            String descriptor,
            List<EvidenceReferenceResponse> evidenceReferences) {}

    public record SequenceLensResponse(
            String type,
            List<SequenceBandResponse> bands,
            List<SequenceRelationResponse> relations,
            List<ClaimResponse> claims) implements LensResponse {}

    public record SequenceBandResponse(
            String id,
            long startMs,
            long endMs,
            boolean parallel,
            List<AnchorResponse> anchors) {}

    public record SequenceRelationResponse(
            String fromBandId,
            String toBandId,
            String relation,
            List<EvidenceReferenceResponse> evidenceReferences) {}

    public record StateLensResponse(
            String type,
            ReceiptResponse receipt,
            List<ClaimResponse> claims) implements LensResponse {}

    public record ChampionTimingLensResponse(
            String type,
            String knowledgeVersion,
            List<ChampionCapabilityResponse> capabilities,
            List<ClaimResponse> claims) implements LensResponse {}

    public record ChampionCapabilityResponse(
            String category, String statement, CapabilityAssertionResponse assertion) {}

    public record CapabilityAssertionResponse(
            String assertionId, String patch, String applicableBuild,
            String entityType, int entityId, List<String> capabilities,
            List<String> prerequisites, String sourceUri, String sourceRevision,
            String reviewerId, String reviewStatus, int reviewRevision,
            String assertionMode, List<String> missingPrerequisites) {}

    public record TransferLensResponse(
            String type,
            List<TransferRowResponse> rows,
            List<ClaimResponse> claims) implements LensResponse {}

    public record TransferRowResponse(
            long representedAtMs,
            String category,
            String observedFor,
            List<EvidenceReferenceResponse> evidenceReferences) {}

    public record ReceiptLensResponse(
            String type,
            ReceiptResponse receipt,
            List<ClaimResponse> claims) implements LensResponse {}

    public record ClaimResponse(
            String claimId,
            String statement,
            String assertionMode,
            List<EvidenceReferenceResponse> evidenceReferences,
            Set<String> limitations) {}

    public record EvidenceResponse(
            List<String> claimIds,
            List<EvidenceReferenceResponse> references,
            List<SourceCoverageResponse> coverage,
            AnalysisRevisionResponse revision) {}

    public record EvidenceReferenceResponse(
            String sourceKind,
            String sourceCaptureId,
            String sourceRecordId,
            long representedAtMs,
            String methodVersion) {}

    public record SourceCoverageResponse(
            String signal,
            String sourceKind,
            String status,
            Long representedStartMs,
            Long representedEndMs,
            String sourceCaptureId,
            String sourceRecordId,
            String methodVersion) {}

    public record AnalysisRevisionResponse(
            String evidenceRevision,
            List<String> historicalMethodVersions,
            String transitionPolicyVersion,
            String receiptProjectionVersion,
            String lensPolicyVersion,
            String episodePolicyVersion,
            String reviewOrderingPolicyVersion,
            String championKnowledgeVersion) {}

    public record ContextualQuestionResponse(
            String id,
            String prompt,
            String answerMode) {}
}
