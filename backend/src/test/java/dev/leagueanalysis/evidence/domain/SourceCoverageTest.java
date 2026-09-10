package dev.leagueanalysis.evidence.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceCoverageTest {
    @Test
    void preservesUnavailableSourceIdentityWithoutInventingACapture() {
        var coverage = new SourceCoverage(
                "participant_positions",
                "MATCH_TIMELINE",
                CoverageStatus.UNAVAILABLE,
                null,
                null,
                uuid(1),
                null,
                "timeline-v1");

        assertThat(coverage.signal()).isEqualTo("participant_positions");
        assertThat(coverage.sourceKind()).isEqualTo("MATCH_TIMELINE");
        assertThat(coverage.sourceRecordId()).isEqualTo(uuid(1));
        assertThat(coverage.sourceCaptureId()).isNull();
    }

    @Test
    void requiresCaptureProvenanceForStatusesThatClaimEvidence() {
        assertThatThrownBy(() -> new SourceCoverage(
                "participant_positions",
                "MATCH_TIMELINE",
                CoverageStatus.OBSERVED,
                0L,
                1_000L,
                uuid(1),
                null,
                "timeline-v1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("sourceCaptureId");
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
