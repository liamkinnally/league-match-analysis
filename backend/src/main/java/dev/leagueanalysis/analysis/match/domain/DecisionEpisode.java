package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.List;
import java.util.Objects;

public record DecisionEpisode(
        String id,
        String transitionId,
        EpisodeKind kind,
        DecisionWindow window,
        String questionId,
        String selectionRationale,
        List<ReviewBeat> beats,
        String compositionRuleVersion) {
    public DecisionEpisode {
        id = TimelineEventKey.requireText(id);
        if (!id.matches("ep_[0-9a-f]{24}")) {
            throw new IllegalArgumentException("INVALID_EPISODE_ID");
        }
        transitionId = TimelineEventKey.requireText(transitionId);
        if (!transitionId.matches("trn_[0-9a-f]{24}")) {
            throw new IllegalArgumentException("INVALID_ANALYTICAL_OBJECT_ID");
        }
        kind = Objects.requireNonNull(kind, "kind");
        window = Objects.requireNonNull(window, "window");
        questionId = TimelineEventKey.requireText(questionId);
        selectionRationale = TimelineEventKey.requireText(selectionRationale);
        beats = List.copyOf(Objects.requireNonNull(beats, "beats"));
        if (beats.size() < 3) {
            throw new IllegalArgumentException("INSUFFICIENT_REVIEW_BEATS");
        }
        compositionRuleVersion = TimelineEventKey.requireText(compositionRuleVersion);
    }

    public enum EpisodeKind {
        CONVERSION,
        MIXED_VALUE,
        ADVERSE_CONSEQUENCE,
        OVERLAPPING_EXCHANGE
    }

    public record DecisionWindow(TimeInterval interval, List<String> exactAnchorKeys) {
        public DecisionWindow {
            interval = Objects.requireNonNull(interval, "interval");
            exactAnchorKeys = List.copyOf(
                    Objects.requireNonNull(exactAnchorKeys, "exactAnchorKeys"));
            if (exactAnchorKeys.isEmpty()) {
                throw new IllegalArgumentException("ANCHOR_KEY_REQUIRED");
            }
        }
    }
}
