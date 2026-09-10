package dev.leagueanalysis.analysis.match.domain;

import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record AnalyticalObjectId(String value) {
    public AnalyticalObjectId {
        value = TimelineEventKey.requireText(value);
        if (!value.matches("trn_[0-9a-f]{24}")) {
            throw new IllegalArgumentException("INVALID_ANALYTICAL_OBJECT_ID");
        }
    }

    public static AnalyticalObjectId transition(
            String matchId,
            int focalParticipantId,
            TimeInterval interval,
            List<TimelineEventKey> exactAnchorKeys,
            String transitionPolicyVersion) {
        Objects.requireNonNull(interval, "interval");
        var keys = Objects.requireNonNull(exactAnchorKeys, "exactAnchorKeys").stream()
                .map(key -> Objects.requireNonNull(key, "exactAnchorKey"))
                .sorted()
                .toList();
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("ANCHOR_KEY_REQUIRED");
        }
        var canonical = new StringBuilder()
                .append("matchId=").append(TimelineEventKey.requireText(matchId)).append('\n')
                .append("focalParticipantId=").append(focalParticipantId).append('\n')
                .append("interval=").append(interval.startMs()).append(':')
                .append(interval.endMs()).append('\n');
        keys.forEach(key -> canonical.append("anchor=")
                .append(key.matchId()).append(':')
                .append(key.representedAtMs()).append(':')
                .append(key.frameAtMs()).append(':')
                .append(key.frameEventIndex()).append(':')
                .append(key.providerEventType()).append('\n'));
        canonical.append("transitionPolicyVersion=")
                .append(TimelineEventKey.requireText(transitionPolicyVersion));
        return new AnalyticalObjectId(
                "trn_" + sha256(canonical.toString()).substring(0, 24));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
