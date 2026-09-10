package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.SourceCoverage;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public record MatchAnalysis(
        MatchSummary match,
        AnalysisRevision revision,
        MatchArc arc,
        Review review,
        Optional<ActiveAnalysis> active) {
    public MatchAnalysis {
        match = Objects.requireNonNull(match, "match");
        revision = Objects.requireNonNull(revision, "revision");
        arc = Objects.requireNonNull(arc, "arc");
        review = Objects.requireNonNull(review, "review");
        active = Objects.requireNonNull(active, "active");
    }

    public String evidenceRevision() {
        return revision.evidenceRevision();
    }

    public record MatchSummary(MatchHeader header, MatchParticipant focalParticipant) {
        public MatchSummary {
            header = Objects.requireNonNull(header, "header");
            focalParticipant = Objects.requireNonNull(focalParticipant, "focalParticipant");
        }
    }

    public record MatchArc(
            List<TransitionPacket> transitions,
            Map<String, LensKind> primaryLenses,
            int eligibleUnselectedCount,
            String selectionRuleVersion,
            Optional<String> emptyReason) {
        public MatchArc {
            transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
            primaryLenses = Map.copyOf(Objects.requireNonNull(primaryLenses, "primaryLenses"));
            if (eligibleUnselectedCount < 0) {
                throw new IllegalArgumentException("INVALID_UNSELECTED_COUNT");
            }
            selectionRuleVersion = TimelineEventKey.requireText(selectionRuleVersion);
            emptyReason = Objects.requireNonNull(emptyReason, "emptyReason")
                    .map(TimelineEventKey::requireText);
        }
    }

    public record Review(
            List<DecisionEpisode> episodes,
            List<String> learningOrder,
            List<String> chronologicalOrder,
            Map<String, String> temporalCues,
            String orderingRuleVersion) {
        public Review {
            episodes = List.copyOf(Objects.requireNonNull(episodes, "episodes"));
            learningOrder = List.copyOf(
                    Objects.requireNonNull(learningOrder, "learningOrder"));
            chronologicalOrder = List.copyOf(
                    Objects.requireNonNull(chronologicalOrder, "chronologicalOrder"));
            temporalCues = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(temporalCues, "temporalCues")));
            orderingRuleVersion = TimelineEventKey.requireText(orderingRuleVersion);
        }
    }

    public record ActiveAnalysis(
            AnalysisContext context,
            String sourceTransitionId,
            StateReceipt receipt,
            LensPayload lens,
            List<EvidenceClaim> claims,
            Set<String> limitations,
            EvidenceBundle evidence,
            List<ContextualQuestion> supportedQuestions,
            Optional<DecisionEpisode> linkedReviewEpisode) {
        public ActiveAnalysis {
            context = Objects.requireNonNull(context, "context");
            sourceTransitionId = TimelineEventKey.requireText(sourceTransitionId);
            receipt = Objects.requireNonNull(receipt, "receipt");
            lens = Objects.requireNonNull(lens, "lens");
            claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
            limitations = Collections.unmodifiableSortedSet(new TreeSet<>(
                    Objects.requireNonNull(limitations, "limitations")));
            evidence = Objects.requireNonNull(evidence, "evidence");
            supportedQuestions = List.copyOf(
                    Objects.requireNonNull(supportedQuestions, "supportedQuestions"));
            linkedReviewEpisode = Objects.requireNonNull(
                    linkedReviewEpisode, "linkedReviewEpisode");
        }
    }

    public record EvidenceBundle(
            AnalysisRevision revision,
            List<EvidenceReference> references,
            List<SourceCoverage> coverage) {
        public EvidenceBundle {
            revision = Objects.requireNonNull(revision, "revision");
            references = List.copyOf(Objects.requireNonNull(references, "references"));
            coverage = List.copyOf(Objects.requireNonNull(coverage, "coverage"));
        }
    }

    public record ContextualQuestion(String id, String prompt, AssertionMode answerMode) {
        public ContextualQuestion {
            id = TimelineEventKey.requireText(id);
            prompt = TimelineEventKey.requireText(prompt);
            answerMode = Objects.requireNonNull(answerMode, "answerMode");
        }
    }
}
