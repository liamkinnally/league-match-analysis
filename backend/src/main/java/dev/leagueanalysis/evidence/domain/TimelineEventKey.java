package dev.leagueanalysis.evidence.domain;

import java.util.Objects;

public record TimelineEventKey(
        String matchId,
        long representedAtMs,
        long frameAtMs,
        int frameEventIndex,
        String providerEventType) implements Comparable<TimelineEventKey> {
    public TimelineEventKey {
        matchId = requireText(matchId);
        providerEventType = requireText(providerEventType);
        if (representedAtMs < 0 || frameAtMs < 0 || frameEventIndex < 0) {
            throw new IllegalArgumentException("NEGATIVE_EVENT_ORDER_VALUE");
        }
    }

    @Override
    public int compareTo(TimelineEventKey other) {
        var byMatch = matchId.compareTo(other.matchId);
        if (byMatch != 0) {
            return byMatch;
        }
        var byRepresentedTime = Long.compare(representedAtMs, other.representedAtMs);
        if (byRepresentedTime != 0) {
            return byRepresentedTime;
        }
        var byFrameTime = Long.compare(frameAtMs, other.frameAtMs);
        if (byFrameTime != 0) {
            return byFrameTime;
        }
        var byEventIndex = Integer.compare(frameEventIndex, other.frameEventIndex);
        if (byEventIndex != 0) {
            return byEventIndex;
        }
        return providerEventType.compareTo(other.providerEventType);
    }

    public static String requireText(String value) {
        var normalized = Objects.requireNonNull(value, "value").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("BLANK_IDENTIFIER");
        }
        return normalized;
    }
}
