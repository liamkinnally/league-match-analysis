package dev.leagueanalysis.analysis.match.adapter.out.knowledge;

import dev.leagueanalysis.analysis.match.application.ChampionKnowledgePort;
import dev.leagueanalysis.analysis.match.application.ChampionKnowledgeQuery;
import dev.leagueanalysis.analysis.match.domain.CapabilityAssertion;
import dev.leagueanalysis.analysis.match.domain.CapabilityAssertion.CapabilityTag;
import dev.leagueanalysis.analysis.match.domain.CapabilityAssertion.EntityType;
import dev.leagueanalysis.analysis.match.domain.CapabilityAssertion.Prerequisite;
import dev.leagueanalysis.analysis.match.domain.QuestionKind;
import dev.leagueanalysis.analysis.match.domain.ReviewStatus;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class ClasspathChampionKnowledgeAdapter implements ChampionKnowledgePort {
    private final Optional<String> knowledgeVersion;
    private final List<CapabilityAssertion> assertions;

    public ClasspathChampionKnowledgeAdapter() {
        this(new ClassPathResource("knowledge/champion-capabilities-16.17.json"));
    }

    public ClasspathChampionKnowledgeAdapter(Resource resource) {
        if (!resource.exists()) {
            knowledgeVersion = Optional.empty();
            assertions = List.of();
            return;
        }
        try (var input = resource.getInputStream()) {
            var document = new ObjectMapper().readTree(input);
            knowledgeVersion = Optional.of(text(document, "knowledgeVersion"));
            var records = document.path("assertions");
            if (!records.isArray()) throw invalid();
            var loaded = new ArrayList<CapabilityAssertion>();
            var ids = new HashSet<String>();
            for (var record : records) {
                var id = text(record, "assertionId");
                if (!ids.add(id)) throw invalid();
                var patch = text(record, "patch");
                if (!patch.matches("[0-9]+\\.[0-9]+")) throw invalid();
                var sourceUri = text(record, "sourceUri");
                var uri = URI.create(sourceUri);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null) throw invalid();
                var prerequisites = tags(record, "prerequisites", Prerequisite.class);
                loaded.add(new CapabilityAssertion(
                        id, patch, null,
                        EntityType.valueOf(text(record, "entityType")),
                        positiveInteger(record, "entityId"),
                        tags(record, "capabilities", CapabilityTag.class), prerequisites,
                        text(record, "summary"), sourceUri, text(record, "sourceRevision"),
                        text(record, "reviewerId"),
                        ReviewStatus.valueOf(text(record, "reviewStatus")),
                        positiveInteger(record, "reviewRevision"), prerequisites));
            }
            assertions = List.copyOf(loaded);
        } catch (IOException | tools.jackson.core.JacksonException exception) {
            throw new IllegalArgumentException("INVALID_CHAMPION_KNOWLEDGE", exception);
        }
    }

    @Override
    public Optional<String> knowledgeVersion() {
        return knowledgeVersion;
    }

    @Override
    public List<CapabilityAssertion> findApplicable(ChampionKnowledgeQuery query) {
        if (query == null || query.gameVersion() == null
                || !query.gameVersion().matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)*")
                || query.observedItemId() == null || query.ownedAtMs() < 0
                || query.questionKind() != QuestionKind.CONVERSION) {
            return List.of();
        }
        var parts = query.gameVersion().split("\\.");
        var patch = parts[0] + "." + parts[1];
        return assertions.stream()
                .filter(assertion -> assertion.reviewStatus() == ReviewStatus.APPROVED)
                .filter(assertion -> assertion.patch().equals(patch))
                .filter(assertion -> assertion.entityType() == EntityType.ITEM
                        && assertion.entityId() == query.observedItemId())
                .map(assertion -> new CapabilityAssertion(
                        assertion.assertionId(), assertion.patch(), query.gameVersion(),
                        assertion.entityType(), assertion.entityId(), assertion.capabilities(),
                        assertion.prerequisites(), assertion.summary(), assertion.sourceUri(),
                        assertion.sourceRevision(), assertion.reviewerId(), assertion.reviewStatus(),
                        assertion.reviewRevision(), assertion.prerequisites().stream()
                                .filter(prerequisite -> prerequisite != Prerequisite.OWNED).toList()))
                .toList();
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) throw invalid();
        return value.asString();
    }

    private static int positiveInteger(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) throw invalid();
        return value.asInt();
    }

    private static <E extends Enum<E>> List<E> tags(JsonNode node, String field, Class<E> type) {
        var values = node.path(field);
        if (!values.isArray() || values.isEmpty()) throw invalid();
        var tags = new ArrayList<E>();
        for (var value : values) {
            if (!value.isString()) throw invalid();
            tags.add(Enum.valueOf(type, value.asString()));
        }
        return List.copyOf(tags);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_CHAMPION_KNOWLEDGE");
    }
}
