package dev.leagueanalysis.analysis.match.adapter.out.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.leagueanalysis.analysis.match.application.ChampionKnowledgePort;
import dev.leagueanalysis.analysis.match.application.ChampionKnowledgeQuery;
import dev.leagueanalysis.analysis.match.domain.AssertionMode;
import dev.leagueanalysis.analysis.match.domain.QuestionKind;
import dev.leagueanalysis.analysis.match.domain.ReviewStatus;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ClasspathChampionKnowledgeAdapterTest {
    private final ChampionKnowledgePort port = new ClasspathChampionKnowledgeAdapter(
            new ClassPathResource("knowledge/champion-capabilities-16.17.json"));

    // Load the reviewed resource, match the build to patch 16.17,
    // and return its applicable capability with expert-maintained provenance.
    @Test
    void returns_only_approved_patch_scoped_applicable_assertions() {
        var assertions = port.findApplicable(new ChampionKnowledgeQuery(
                "16.17.810.4348", 86, 6631, 814_821, QuestionKind.CONVERSION));

        assertThat(assertions).singleElement().satisfies(assertion -> {
            assertThat(assertion.assertionId()).isEqualTo("item-6631-active-slow-movement-v1");
            assertThat(assertion.assertionMode()).isEqualTo(AssertionMode.EXPERT_MAINTAINED);
            assertThat(assertion.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
            assertThat(assertion.sourceRevision()).isEqualTo("16.17.1");
            assertThat(assertion.sourceUri()).isEqualTo(
                    "https://ddragon.leagueoflegends.com/cdn/16.17.1/data/en_US/item.json");
        });
    }

    // A reviewed 16.17 record must not apply to another patch.
    @Test
    void abstains_for_patch_mismatch() {
        assertThat(port.findApplicable(new ChampionKnowledgeQuery(
                "16.18", 86, 6631, 814_821, QuestionKind.CONVERSION))).isEmpty();
    }

    // Champion identity and an observation time do not establish
    // item ownership; a null observed item identity cannot satisfy OWNED.
    @Test
    void abstains_without_observed_ownership() {
        assertThat(port.findApplicable(new ChampionKnowledgeQuery(
                "16.17.810.4348", 86, null, 814_821, QuestionKind.CONVERSION))).isEmpty();
    }

    // Parse review status and exclude both non-current statuses;
    // the APPROVED control proves this same resource boundary can admit the record.
    @ParameterizedTest
    @ValueSource(strings = {"WITHDRAWN", "SUPERSEDED"})
    void abstains_for_withdrawn_or_superseded_revisions(String reviewStatus) {
        var query = new ChampionKnowledgeQuery(
                "16.17.810.4348", 86, 6631, 814_821, QuestionKind.CONVERSION);

        assertThat(portWithStatus("APPROVED").findApplicable(query)).hasSize(1);
        assertThat(portWithStatus(reviewStatus).findApplicable(query)).isEmpty();
    }

    // The sole conversion capability must not be returned merely
    // because a non-conversion question has an item identity and timestamp.
    @Test
    void abstains_for_an_irrelevant_question() {
        assertThat(port.findApplicable(new ChampionKnowledgeQuery(
                "16.17.810.4348", 86, 6631, 814_821,
                QuestionKind.ADVERSE_CONSEQUENCE))).isEmpty();
    }

    // Duplicate logical identities must fail during loading.
    @Test
    void rejects_duplicate_assertion_ids() {
        var document = new ObjectMapper().readTree(knowledgeJson("APPROVED"));
        document.withArray("assertions").add(document.get("assertions").get(0).deepCopy());
        assertThatThrownBy(() -> portFor(document.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Every provenance component is required for a reviewed claim.
    @ParameterizedTest
    @ValueSource(strings = {"sourceUri", "sourceRevision", "reviewerId"})
    void rejects_blank_provenance(String field) {
        var document = new ObjectMapper().readTree(knowledgeJson("APPROVED"));
        ((ObjectNode) document.get("assertions").get(0)).put(field, " ");
        assertThatThrownBy(() -> portFor(document.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Unrecognized controlled vocabulary must never be admitted.
    @ParameterizedTest
    @ValueSource(strings = {"SLOW", "OWNED", "APPROVED", "ITEM"})
    void rejects_unknown_capability_prerequisite_status_or_entity(String token) {
        assertThatThrownBy(() -> portFor(
                knowledgeJson("APPROVED").replace("\"" + token + "\"", "\"UNREVIEWED_VALUE\"")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // A review revision must be a positive integer, without coercion.
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "null", "\"1\""})
    void rejects_invalid_review_revision(String revision) {
        assertThatThrownBy(() -> portFor(knowledgeJson("APPROVED")
                .replace("\"reviewRevision\": 1", "\"reviewRevision\": " + revision)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Records without patch scope cannot be safely matched.
    @Test
    void rejects_absent_patch() {
        assertThatThrownBy(() -> portFor(knowledgeJson("APPROVED")
                .replace("\"patch\": \"16.17\",", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ChampionKnowledgePort portWithStatus(String reviewStatus) {
        return portFor(knowledgeJson(reviewStatus));
    }

    private ChampionKnowledgePort portFor(String json) {
        return new ClasspathChampionKnowledgeAdapter(
                new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)));
    }

    private String knowledgeJson(String reviewStatus) {
        return """
                {
                  "knowledgeVersion": "champion-capabilities-16.17-p3-v1",
                  "assertions": [{
                    "assertionId": "item-6631-active-slow-movement-v1",
                    "patch": "16.17",
                    "entityType": "ITEM",
                    "entityId": 6631,
                    "capabilities": ["SLOW", "MOVEMENT_SPEED"],
                    "prerequisites": ["OWNED", "ACTIVE_USE_REQUIRED", "CHAMPION_HIT_FOR_MOVEMENT"],
                    "summary": "Its active can slow nearby enemies and grant decaying movement speed per champion hit.",
                    "sourceUri": "https://ddragon.leagueoflegends.com/cdn/16.17.1/data/en_US/item.json",
                    "sourceRevision": "16.17.1",
                    "reviewerId": "league-analysis-product-owner",
                    "reviewStatus": "%s",
                    "reviewRevision": 1
                  }]
                }
                """.formatted(reviewStatus);
    }
}
