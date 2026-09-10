package dev.leagueanalysis.analysis.match.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EvidenceRevisionFactoryTest {
    private final EvidenceRevisionFactory factory = new EvidenceRevisionFactory();

    @Test
    void hashes_the_frozen_source_and_rule_bundle_with_sha_256() {
        var revision = factory.create(
                MatchAnalysisTestFixture.snapshot(),
                "material-transition-p3-v1",
                "state-receipt-p3-v1",
                "lens-policy-p3-v1",
                "review-order-p3-v1");

        assertThat(revision).isEqualTo(
                "ev_bf827dfe9d5684fcc3744d34101ee6d8edf35e2127c57dd23006324c432c9de2");
    }

    @Test
    void row_uuids_and_input_order_do_not_change_the_revision() {
        var baseline = revisionFor(MatchAnalysisTestFixture.snapshot());

        assertThat(revisionFor(MatchAnalysisTestFixture.withDifferentRowIds()))
                .isEqualTo(baseline);
        assertThat(revisionFor(MatchAnalysisTestFixture.reordered()))
                .isEqualTo(baseline);
    }

    @Test
    void capture_materialization_reconstruction_and_policy_versions_change_the_revision() {
        var baseline = revisionFor(MatchAnalysisTestFixture.snapshot());
        var changedCapture = MatchAnalysisTestFixture.withRevision(
                MatchAnalysisTestFixture.uuid(44),
                MatchAnalysisTestFixture.uuid(55),
                "sanitized-materializer-v1",
                "sanitized-observation-v1",
                "sanitized-event-v1");
        var changedMaterialization = MatchAnalysisTestFixture.withRevision(
                MatchAnalysisTestFixture.DETAIL_CAPTURE_ID,
                MatchAnalysisTestFixture.TIMELINE_CAPTURE_ID,
                "sanitized-materializer-v2",
                "sanitized-observation-v1",
                "sanitized-event-v1");
        var changedReconstruction = MatchAnalysisTestFixture.withRevision(
                MatchAnalysisTestFixture.DETAIL_CAPTURE_ID,
                MatchAnalysisTestFixture.TIMELINE_CAPTURE_ID,
                "sanitized-materializer-v1",
                "sanitized-observation-v2",
                "sanitized-event-v2");

        assertThat(revisionFor(changedCapture)).isNotEqualTo(baseline);
        assertThat(revisionFor(changedMaterialization)).isNotEqualTo(baseline);
        assertThat(revisionFor(changedReconstruction)).isNotEqualTo(baseline);
        assertThat(factory.create(
                MatchAnalysisTestFixture.snapshot(),
                "material-transition-p3-v2",
                "state-receipt-p3-v1",
                "lens-policy-p3-v1",
                "review-order-p3-v1")).isNotEqualTo(baseline);
    }

    private String revisionFor(
            dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot snapshot) {
        return factory.create(
                snapshot,
                "material-transition-p3-v1",
                "state-receipt-p3-v1",
                "lens-policy-p3-v1",
                "review-order-p3-v1");
    }
}
