package dev.leagueanalysis.analysis.match.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.match.adapter.in.web.MatchAnalysisResponseMapper;
import dev.leagueanalysis.analysis.match.adapter.out.knowledge.ClasspathChampionKnowledgeAdapter;
import dev.leagueanalysis.analysis.match.domain.AssertionMode;
import dev.leagueanalysis.analysis.match.domain.ChampionTimingLens;
import dev.leagueanalysis.analysis.match.domain.LensKind;
import dev.leagueanalysis.analysis.match.domain.MatchAnalysis;
import dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot;
import dev.leagueanalysis.analysis.match.domain.MatchHeader;
import dev.leagueanalysis.analysis.match.domain.TransitionPacket;
import dev.leagueanalysis.evidence.domain.ItemTransition;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

class ChampionTimingIntegrationTest {
    // Join exact reconstructed ownership to later focal participation,
    // expose separate claims, and carry reviewed provenance through the real response mapper.
    @Test
    void admits_e2_ownership_before_participation_with_separate_qualified_claims() {
        var service = service(snapshotWith(List.of(purchase(814_821, 6))), knowledge());
        var result = selected(service, 780_275, LensKind.CHAMPION_TIMING);
        var active = result.active().orElseThrow();

        assertThat(active.context().selectedLens()).isEqualTo(LensKind.CHAMPION_TIMING);
        var lens = (ChampionTimingLens) active.lens();
        assertThat(lens.claims()).anySatisfy(claim -> {
            assertThat(claim.assertionMode()).isEqualTo(AssertionMode.RECONSTRUCTED);
            assertThat(claim.statement()).isEqualTo(
                    "Stridebreaker became owned at 13:34.821 (item 6631).");
            assertThat(claim.evidenceReferences()).contains(purchase(814_821, 6).evidence());
        });
        assertThat(lens.claims()).anySatisfy(claim -> {
            assertThat(claim.assertionMode()).isEqualTo(AssertionMode.EXPERT_MAINTAINED);
            assertThat(claim.statement()).isEqualTo(
                    "Its active can slow nearby enemies and grant decaying movement speed per champion hit.");
        });
        assertThat(lens.claims()).anySatisfy(claim -> {
            assertThat(claim.assertionMode()).isEqualTo(AssertionMode.UNKNOWN);
            assertThat(claim.statement()).isEqualTo(
                    "Active use, readiness, and causal impact are unknown.");
        });
        var json = new ObjectMapper().valueToTree(MatchAnalysisResponseMapper.from(result));
        assertThat(json.at("/active/lens/capabilities/0/assertion/sourceRevision").asString())
                .isEqualTo("16.17.1");
        assertThat(json.at("/active/lens/capabilities/0/assertion/reviewerId").asString())
                .isEqualTo("league-analysis-product-owner");
        assertThat(json.at("/active/lens/capabilities/0/assertion/missingPrerequisites").toString())
                .isEqualTo("[\"ACTIVE_USE_REQUIRED\",\"CHAMPION_HIT_FOR_MOVEMENT\"]");
        assertThat(active.claims()).containsAll(lens.claims());
    }

    // Item and level evidence in E3 does not make its question applicable;
    // the port must not be called without a relevant exact ownership link.
    @Test
    void e3_item_and_level_data_do_not_admit_or_query_champion_context() {
        var service = service(snapshotWith(List.of(purchase(1_100_000, 6))), query -> {
            throw new AssertionError("Knowledge queried without a relevant ownership link");
        });
        var active = selected(service, 1_080_335, LensKind.CHAMPION_TIMING).active().orElseThrow();

        assertThat(active.context().availableLenses()).doesNotContain(LensKind.CHAMPION_TIMING);
        assertThat(active.context().selectedLens()).isEqualTo(LensKind.RECEIPT);
        assertThat(active.limitations()).contains("REQUESTED_LENS_UNAVAILABLE");
    }

    // Versioned knowledge is an actual analysis input and must invalidate
    // old restoration revisions, consistently in calm and selected responses.
    @Test
    void knowledge_version_changes_evidence_revision_without_changing_receipt_or_sequence() throws Exception {
        var snapshot = snapshotWith(List.of(purchase(814_821, 6)));
        var resource = new ClassPathResource("knowledge/champion-capabilities-16.17.json");
        var original = resource.getContentAsString(StandardCharsets.UTF_8);
        var revised = new ClasspathChampionKnowledgeAdapter(new ByteArrayResource(original
                .replace("champion-capabilities-16.17-p3-v1", "champion-capabilities-16.17-p3-v2")
                .getBytes(StandardCharsets.UTF_8)));
        var firstService = service(snapshot, knowledge());
        var secondService = service(snapshot, revised);
        var first = selected(firstService, 780_275, LensKind.SEQUENCE);
        var second = selected(secondService, 780_275, LensKind.SEQUENCE);

        assertThat(first.evidenceRevision()).isNotEqualTo(second.evidenceRevision());
        assertThat(first.revision().championKnowledgeVersion())
                .contains("champion-capabilities-16.17-p3-v1");
        assertThat(first.active().orElseThrow().lens()).isEqualTo(second.active().orElseThrow().lens());
        assertThat(first.active().orElseThrow().receipt()).isEqualTo(second.active().orElseThrow().receipt());
        var withoutKnowledge = selected(service(snapshot, query -> List.of()), 780_275, LensKind.SEQUENCE);
        assertThat(first.active().orElseThrow().lens())
                .isEqualTo(withoutKnowledge.active().orElseThrow().lens());
        assertThat(first.active().orElseThrow().claims())
                .isEqualTo(withoutKnowledge.active().orElseThrow().claims());
    }

    // Bug: unused knowledge must not enter the restoration revision, whether the
    // loader abstains for patch/status applicability or has no capability records.
    @ParameterizedTest
    @ValueSource(strings = {"PATCH_MISMATCH", "EMPTY", "WITHDRAWN"})
    void unused_knowledge_version_changes_preserve_revision_and_restoration(String shape) throws Exception {
        var snapshot = snapshotWith(List.of(purchase(814_821, 6)));
        if (shape.equals("PATCH_MISMATCH")) {
            var header = snapshot.header();
            snapshot = new MatchEvidenceSnapshot(new MatchHeader(
                    header.matchId(), header.queueId(), header.mapId(), header.gameMode(), header.gameType(),
                    "16.18", header.dataVersion(), header.gameCreationMs(), header.gameStartMs(),
                    header.gameEndMs(), header.durationMs()), snapshot.sourceRevision(), snapshot.participants(),
                    snapshot.observations(), snapshot.anchors(), snapshot.itemTransitions(),
                    snapshot.observedEndItems(), snapshot.coverage());
        }
        var original = new ClassPathResource("knowledge/champion-capabilities-16.17.json")
                .getContentAsString(StandardCharsets.UTF_8);
        original = switch (shape) {
            case "EMPTY" -> """
                    {"knowledgeVersion":"champion-capabilities-16.17-p3-v1","assertions":[]}
                    """;
            case "WITHDRAWN" -> original.replace("\"APPROVED\"", "\"WITHDRAWN\"");
            default -> original;
        };
        var revised = original.replace(
                "champion-capabilities-16.17-p3-v1", "champion-capabilities-16.17-p3-v2");
        var firstService = service(snapshot, new ClasspathChampionKnowledgeAdapter(
                new ByteArrayResource(original.getBytes(StandardCharsets.UTF_8))));
        var secondService = service(snapshot, new ClasspathChampionKnowledgeAdapter(
                new ByteArrayResource(revised.getBytes(StandardCharsets.UTF_8))));
        var first = selected(firstService, 780_275, LensKind.CHAMPION_TIMING);
        var second = selected(secondService, 780_275, LensKind.CHAMPION_TIMING);

        assertThat(second.evidenceRevision()).isEqualTo(first.evidenceRevision());
        assertThat(first.revision().championKnowledgeVersion()).isEmpty();
        assertThat(second.revision().championKnowledgeVersion()).isEmpty();
        assertThat(second.active().orElseThrow().context().availableLenses())
                .doesNotContain(LensKind.CHAMPION_TIMING);
        assertThat(second.active().orElseThrow().lens()).isEqualTo(first.active().orElseThrow().lens());
        var context = first.active().orElseThrow().context();
        var restored = secondService.analyze(new AnalysisRequest(context.matchId(), context.focalParticipantId(),
                context.selectedObjectId(), context.interval().startMs(), context.interval().endMs(),
                context.questionId(), first.evidenceRevision(), LensKind.CHAMPION_TIMING)).orElseThrow();
        assertThat(restored.active().orElseThrow().context().selectedLens()).isEqualTo(LensKind.RECEIPT);
    }

    // End inventory, another participant's purchase, or a purchase
    // after the later sequence cannot establish the required ownership transition.
    @ParameterizedTest
    @ValueSource(strings = {"END_ITEMS_ONLY", "OTHER_PARTICIPANT", "AFTER_PARTICIPATION", "AMBIGUOUS"})
    void abstains_without_an_exact_focal_ownership_link(String shape) {
        var items = switch (shape) {
            case "OTHER_PARTICIPANT" -> List.of(purchase(814_821, 1));
            case "AFTER_PARTICIPATION" -> List.of(purchase(899_000, 6));
            case "AMBIGUOUS" -> List.of(purchase(814_821, 6), new ItemTransition(
                    purchase(814_821, 6).key(), 6, 3006, null, null,
                    purchase(814_821, 6).evidence()));
            default -> List.<ItemTransition>of();
        };
        var service = service(snapshotWith(items), query -> {
            throw new AssertionError("Knowledge queried without exact focal ownership");
        });
        assertThat(selected(service, 780_275, LensKind.CHAMPION_TIMING)
                .active().orElseThrow().context().availableLenses())
                .doesNotContain(LensKind.CHAMPION_TIMING);
    }

    // Selling or undoing the purchase before participation removes
    // the ownership prerequisite, even though the purchase itself was observed.
    @ParameterizedTest
    @ValueSource(strings = {"ITEM_SOLD", "ITEM_UNDO"})
    void abstains_when_ownership_ends_before_participation(String type) {
        var purchase = purchase(814_821, 6);
        var removal = new ItemTransition(
                new TimelineEventKey(MatchAnalysisTestFixture.MATCH_ID, 830_000, 830_000, 0, type),
                6, type.equals("ITEM_UNDO") ? null : 6631,
                type.equals("ITEM_UNDO") ? 6631 : null,
                type.equals("ITEM_UNDO") ? 0 : null, purchase.evidence());
        var service = service(snapshotWith(List.of(purchase, removal)), query -> {
            throw new AssertionError("Knowledge queried after ownership ended");
        });
        assertThat(selected(service, 780_275, LensKind.CHAMPION_TIMING)
                .active().orElseThrow().context().availableLenses())
                .doesNotContain(LensKind.CHAMPION_TIMING);
    }

    // Absent knowledge must omit the lens and preserve the receipt fallback.
    @Test
    void missing_knowledge_is_abstention_not_an_analysis_failure() {
        var missing = new ClasspathChampionKnowledgeAdapter(new ClassPathResource("knowledge/absent.json"));
        var result = selected(service(snapshotWith(List.of(purchase(814_821, 6))), missing),
                780_275, LensKind.CHAMPION_TIMING);
        assertThat(result.active().orElseThrow().context().selectedLens()).isEqualTo(LensKind.RECEIPT);
        assertThat(result.revision().championKnowledgeVersion()).isEmpty();
    }

    // Another purchase while already owned does not prove a new
    // not-owned to owned transition at the claimed timestamp.
    @Test
    void abstains_when_the_item_was_already_owned_before_the_selected_purchase() {
        var service = service(snapshotWith(List.of(purchase(700_000, 6), purchase(814_821, 6))), query -> {
            throw new AssertionError("Already-owned item is not a new ownership breakpoint");
        });
        assertThat(selected(service, 780_275, LensKind.CHAMPION_TIMING)
                .active().orElseThrow().context().availableLenses())
                .doesNotContain(LensKind.CHAMPION_TIMING);
    }

    // Missing item-event coverage leaves possible inventory changes
    // between purchase and participation unknown, even with a purchase record.
    @Test
    void abstains_when_item_event_coverage_is_missing() {
        var base = snapshotWith(List.of(purchase(814_821, 6)));
        var incomplete = new MatchEvidenceSnapshot(base.header(), base.sourceRevision(), base.participants(),
                base.observations(), base.anchors(), base.itemTransitions(), base.observedEndItems(),
                base.coverage().stream().filter(coverage -> !coverage.signal().equals("item_events")).toList());
        var service = service(incomplete, query -> {
            throw new AssertionError("Missing item-event coverage cannot support the ownership link");
        });
        assertThat(selected(service, 780_275, LensKind.CHAMPION_TIMING)
                .active().orElseThrow().context().availableLenses())
                .doesNotContain(LensKind.CHAMPION_TIMING);
    }

    private ClasspathChampionKnowledgeAdapter knowledge() {
        return new ClasspathChampionKnowledgeAdapter();
    }

    private MatchAnalysisService service(MatchEvidenceSnapshot snapshot, ChampionKnowledgePort knowledge) {
        return new MatchAnalysisService(matchId -> Optional.of(snapshot), knowledge);
    }

    private MatchAnalysis selected(MatchAnalysisService service, long startMs, LensKind lens) {
        var calm = service.analyze(new AnalysisRequest(MatchAnalysisTestFixture.MATCH_ID, 6,
                null, null, null, null, null, null)).orElseThrow();
        var packet = calm.arc().transitions().stream()
                .filter(value -> value.interval().startMs() == startMs).findFirst().orElseThrow();
        return service.analyze(new AnalysisRequest(MatchAnalysisTestFixture.MATCH_ID, 6,
                packet.transitionId(), packet.interval().startMs(), packet.interval().endMs(),
                packet.questionKind().questionId(), calm.evidenceRevision(), lens)).orElseThrow();
    }

    private MatchEvidenceSnapshot snapshotWith(List<ItemTransition> added) {
        var base = MatchAnalysisTestFixture.snapshot();
        var endItems = new java.util.HashMap<>(base.observedEndItems());
        endItems.put(6, List.of(6631, 0, 0, 0, 0, 0, 0));
        var items = new ArrayList<>(base.itemTransitions());
        items.addAll(added);
        return new MatchEvidenceSnapshot(base.header(), base.sourceRevision(), base.participants(),
                base.observations(), base.anchors(), items, endItems, base.coverage());
    }

    private ItemTransition purchase(long timeMs, int participantId) {
        return new ItemTransition(
                new TimelineEventKey(MatchAnalysisTestFixture.MATCH_ID, timeMs, timeMs, 0, "ITEM_PURCHASED"),
                participantId, 6631, null, null,
                new dev.leagueanalysis.evidence.domain.EvidenceReference(
                        MatchAnalysisTestFixture.TIMELINE_CAPTURE_ID,
                        MatchAnalysisTestFixture.uuid(811), timeMs, "sanitized-event-v1"));
    }
}
