package dev.leagueanalysis.analysis.match.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.leagueanalysis.analysis.match.domain.LensKind;
import dev.leagueanalysis.analysis.match.domain.MatchAnalysis;
import dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot;
import dev.leagueanalysis.analysis.match.domain.TransitionPacket;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class MatchAnalysisServiceTest {
    @Test
    void analyzes_calm_explore_and_a_selected_transition() {
        var service = new MatchAnalysisService(queryFor(MatchAnalysisTestFixture.snapshot()));

        var calm = service.analyze(calmRequest()).orElseThrow();

        assertThat(calm.arc().transitions()).hasSizeBetween(3, 5);
        assertThat(calm.review().episodes()).hasSize(4);
        assertThat(calm.active()).isEmpty();

        var packet = calm.arc().transitions().get(1);
        var selected = service.analyze(requestFor(calm, packet)).orElseThrow();

        assertThat(selected.active()).hasValueSatisfying(active -> {
            assertThat(active.context().selectedObjectId()).isEqualTo(packet.transitionId());
            assertThat(active.context().selectedLens()).isEqualTo(LensKind.SEQUENCE);
            assertThat(active.receipt()).isEqualTo(packet.receipt());
            assertThat(active.claims()).allSatisfy(claim ->
                    assertThat(claim.assertionMode()).isNotNull());
            assertThat(active.lens().claims())
                    .containsExactlyElementsOf(active.claims());
        });
    }

    @Test
    void maps_an_episode_object_to_its_single_source_transition() {
        var service = new MatchAnalysisService(queryFor(MatchAnalysisTestFixture.snapshot()));
        var calm = service.analyze(calmRequest()).orElseThrow();
        var episode = calm.review().episodes().getFirst();
        var packet = calm.arc().transitions().stream()
                .filter(candidate -> candidate.transitionId().equals(episode.transitionId()))
                .findFirst()
                .orElseThrow();
        var request = new AnalysisRequest(
                MatchAnalysisTestFixture.MATCH_ID, 6, episode.id(),
                packet.interval().startMs(), packet.interval().endMs(),
                packet.questionKind().questionId(), calm.evidenceRevision(), LensKind.RECEIPT);

        var selected = service.analyze(request).orElseThrow();

        assertThat(selected.active()).hasValueSatisfying(active -> {
            assertThat(active.context().selectedObjectId()).isEqualTo(episode.id());
            assertThat(active.sourceTransitionId()).isEqualTo(packet.transitionId());
            assertThat(active.linkedReviewEpisode()).contains(episode);
        });
    }

    @Test
    void rejects_a_stale_requested_revision_with_the_current_revision() {
        var service = new MatchAnalysisService(queryFor(MatchAnalysisTestFixture.snapshot()));
        var calm = service.analyze(calmRequest()).orElseThrow();
        var packet = calm.arc().transitions().getFirst();
        var request = new AnalysisRequest(
                MatchAnalysisTestFixture.MATCH_ID, 6, packet.transitionId(),
                packet.interval().startMs(), packet.interval().endMs(),
                packet.questionKind().questionId(), "ev_" + "0".repeat(64), LensKind.RECEIPT);

        assertThatThrownBy(() -> service.analyze(request))
                .isInstanceOfSatisfying(MatchAnalysisException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(
                            MatchAnalysisException.Code.STALE_EVIDENCE_REVISION);
                    assertThat(exception.currentEvidenceRevision())
                            .contains(calm.evidenceRevision());
                });
    }

    @Test
    void rejects_a_focal_participant_that_is_not_in_the_match() {
        var base = MatchAnalysisTestFixture.snapshot();
        var withoutFocalParticipant = new MatchEvidenceSnapshot(
                base.header(), base.sourceRevision(),
                base.participants().stream()
                        .filter(participant -> participant.participantId() != 6)
                        .toList(),
                base.observations(), base.anchors(), base.itemTransitions(),
                base.observedEndItems(), base.coverage());
        var service = new MatchAnalysisService(queryFor(withoutFocalParticipant));

        assertThatThrownBy(() -> service.analyze(new AnalysisRequest(
                MatchAnalysisTestFixture.MATCH_ID, 6,
                null, null, null, null, null, null)))
                .isInstanceOfSatisfying(MatchAnalysisException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MatchAnalysisException.Code.FOCAL_PARTICIPANT_NOT_FOUND));
    }

    @Test
    void returns_a_truthful_empty_arc_when_no_transition_is_eligible() {
        var base = MatchAnalysisTestFixture.snapshot();
        var withoutAnchors = new MatchEvidenceSnapshot(
                base.header(), base.sourceRevision(), base.participants(),
                base.observations(), List.of(), base.itemTransitions(),
                base.observedEndItems(), base.coverage());
        var service = new MatchAnalysisService(queryFor(withoutAnchors));

        var analysis = service.analyze(calmRequest()).orElseThrow();

        assertThat(analysis.arc().transitions()).isEmpty();
        assertThat(analysis.arc().emptyReason()).contains("NO_ELIGIBLE_TRANSITIONS");
        assertThat(analysis.review().episodes()).isEmpty();
        assertThat(analysis.active()).isEmpty();
    }

    @Test
    void reports_eligible_transitions_left_out_by_the_selection_cap() {
        var service = new MatchAnalysisService(queryFor(
                MatchAnalysisTestFixture.overCapDirectionAndPhaseCoverage()));

        var analysis = service.analyze(calmRequest()).orElseThrow();

        assertThat(analysis.arc().transitions()).hasSize(5);
        assertThat(analysis.arc().eligibleUnselectedCount()).isEqualTo(1);
    }

    @Test
    void returns_empty_when_the_match_does_not_exist() {
        var service = new MatchAnalysisService(queryFor(null));

        assertThat(service.analyze(calmRequest())).isEmpty();
    }

    @Test
    void performs_exactly_one_load_inside_exactly_one_read_snapshot() {
        var query = new CountingQuery(MatchAnalysisTestFixture.snapshot());
        var service = new MatchAnalysisService(query);

        service.analyze(calmRequest()).orElseThrow();

        assertThat(query.snapshotCalls).isEqualTo(1);
        assertThat(query.loadCalls).isEqualTo(1);
        assertThat(query.loadWasInsideSnapshot).isTrue();
    }

    private AnalysisRequest calmRequest() {
        return new AnalysisRequest(
                MatchAnalysisTestFixture.MATCH_ID, 6,
                null, null, null, null, null, null);
    }

    private AnalysisRequest requestFor(MatchAnalysis analysis, TransitionPacket packet) {
        return new AnalysisRequest(
                MatchAnalysisTestFixture.MATCH_ID, 6, packet.transitionId(),
                packet.interval().startMs(), packet.interval().endMs(),
                packet.questionKind().questionId(), analysis.evidenceRevision(),
                LensKind.SEQUENCE);
    }

    private HistoricalMatchQuery queryFor(MatchEvidenceSnapshot snapshot) {
        return new CountingQuery(snapshot);
    }

    private static final class CountingQuery implements HistoricalMatchQuery {
        private final MatchEvidenceSnapshot snapshot;
        private int snapshotCalls;
        private int loadCalls;
        private boolean insideSnapshot;
        private boolean loadWasInsideSnapshot;

        private CountingQuery(MatchEvidenceSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public <T> T inReadSnapshot(Supplier<T> work) {
            snapshotCalls++;
            insideSnapshot = true;
            try {
                return work.get();
            } finally {
                insideSnapshot = false;
            }
        }

        @Override
        public Optional<MatchEvidenceSnapshot> load(String matchId) {
            loadCalls++;
            loadWasInsideSnapshot = insideSnapshot;
            return Optional.ofNullable(snapshot);
        }
    }
}
