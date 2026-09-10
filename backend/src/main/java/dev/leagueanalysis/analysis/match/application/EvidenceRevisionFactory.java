package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeSet;

public final class EvidenceRevisionFactory {
    public String create(
            MatchEvidenceSnapshot snapshot,
            String transitionPolicyVersion,
            String receiptVersion,
            String... additionalPolicyVersions) {
        Objects.requireNonNull(snapshot, "snapshot");
        var sourceRevision = snapshot.sourceRevision();
        var canonical = new StringBuilder()
                .append("matchId=").append(snapshot.header().matchId()).append('\n')
                .append("detailCaptureId=")
                .append(sourceRevision.detailCaptureId()).append('\n')
                .append("timelineCaptureId=")
                .append(sourceRevision.timelineCaptureId() == null
                        ? "UNAVAILABLE"
                        : sourceRevision.timelineCaptureId())
                .append('\n')
                .append("materializationVersion=")
                .append(sourceRevision.materializationVersion()).append('\n');
        historicalMethodVersions(snapshot).forEach(version -> canonical
                .append("historicalMethodVersion=").append(version).append('\n'));
        canonical.append("transitionPolicyVersion=")
                .append(TimelineEventKey.requireText(transitionPolicyVersion)).append('\n')
                .append("receiptVersion=")
                .append(TimelineEventKey.requireText(receiptVersion));
        var additional = Arrays.stream(Objects.requireNonNull(
                        additionalPolicyVersions, "additionalPolicyVersions"))
                .map(TimelineEventKey::requireText)
                .toList();
        for (int index = 0; index < additional.size(); index++) {
            canonical.append('\n').append("additionalPolicyVersion[")
                    .append(index).append("]=").append(additional.get(index));
        }
        return "ev_" + sha256(canonical.toString());
    }

    private TreeSet<String> historicalMethodVersions(MatchEvidenceSnapshot snapshot) {
        var versions = new TreeSet<String>();
        snapshot.observations().forEach(observation ->
                versions.add(observation.evidence().methodVersion()));
        snapshot.anchors().forEach(anchor -> anchor.evidenceReferences().forEach(reference ->
                versions.add(reference.methodVersion())));
        snapshot.itemTransitions().forEach(transition ->
                transition.evidenceReferences().forEach(reference ->
                        versions.add(reference.methodVersion())));
        snapshot.coverage().forEach(coverage -> versions.add(coverage.methodVersion()));
        return versions;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
