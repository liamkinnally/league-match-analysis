package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.AnalysisContext;
import dev.leagueanalysis.analysis.match.domain.AnalysisRevision;
import dev.leagueanalysis.analysis.match.domain.AssertionMode;
import dev.leagueanalysis.analysis.match.domain.AnchorKind;
import dev.leagueanalysis.analysis.match.domain.ChampionTimingLens;
import dev.leagueanalysis.analysis.match.domain.EvidenceClaim;
import dev.leagueanalysis.analysis.match.domain.QuestionKind;
import dev.leagueanalysis.analysis.match.domain.ReviewStatus;
import dev.leagueanalysis.analysis.match.domain.DecisionEpisode;
import dev.leagueanalysis.analysis.match.domain.MatchAnalysis;
import dev.leagueanalysis.analysis.match.domain.MatchAnalysis.ActiveAnalysis;
import dev.leagueanalysis.analysis.match.domain.MatchAnalysis.ContextualQuestion;
import dev.leagueanalysis.analysis.match.domain.MatchAnalysis.EvidenceBundle;
import dev.leagueanalysis.analysis.match.domain.MatchAnalysis.MatchArc;
import dev.leagueanalysis.analysis.match.domain.MatchAnalysis.MatchSummary;
import dev.leagueanalysis.analysis.match.domain.MatchAnalysis.Review;
import dev.leagueanalysis.analysis.match.domain.MatchEvidenceSnapshot;
import dev.leagueanalysis.analysis.match.domain.MatchParticipant;
import dev.leagueanalysis.analysis.match.domain.TransitionPacket;
import dev.leagueanalysis.evidence.application.InventoryProjector;
import dev.leagueanalysis.evidence.domain.CoverageStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class MatchAnalysisService {
    private final HistoricalMatchQuery query;
    private final TransitionSelector transitionSelector;
    private final LensAdmissionPolicy lensAdmissionPolicy;
    private final DecisionEpisodeComposer episodeComposer;
    private final ReviewOrderingPolicy reviewOrderingPolicy;
    private final EvidenceRevisionFactory revisionFactory;
    private final ChampionKnowledgePort knowledge;

    @Autowired
    public MatchAnalysisService(HistoricalMatchQuery query, ChampionKnowledgePort knowledge) {
        this(
                query,
                new TransitionSelector(),
                new LensAdmissionPolicy(),
                new DecisionEpisodeComposer(),
                new ReviewOrderingPolicy(),
                new EvidenceRevisionFactory(), knowledge);
    }

    public MatchAnalysisService(HistoricalMatchQuery query) {
        this(query, ignored -> List.of());
    }

    MatchAnalysisService(
            HistoricalMatchQuery query,
            TransitionSelector transitionSelector,
            LensAdmissionPolicy lensAdmissionPolicy,
            DecisionEpisodeComposer episodeComposer,
            ReviewOrderingPolicy reviewOrderingPolicy,
            EvidenceRevisionFactory revisionFactory,
            ChampionKnowledgePort knowledge) {
        this.query = Objects.requireNonNull(query, "query");
        this.transitionSelector = Objects.requireNonNull(
                transitionSelector, "transitionSelector");
        this.lensAdmissionPolicy = Objects.requireNonNull(
                lensAdmissionPolicy, "lensAdmissionPolicy");
        this.episodeComposer = Objects.requireNonNull(episodeComposer, "episodeComposer");
        this.reviewOrderingPolicy = Objects.requireNonNull(
                reviewOrderingPolicy, "reviewOrderingPolicy");
        this.revisionFactory = Objects.requireNonNull(revisionFactory, "revisionFactory");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    }

    public Optional<MatchAnalysis> analyze(AnalysisRequest request) {
        Objects.requireNonNull(request, "request");
        return query.inReadSnapshot(() -> query.load(request.matchId())
                .map(snapshot -> analyzeSnapshot(snapshot, request)));
    }

    private MatchAnalysis analyzeSnapshot(
            MatchEvidenceSnapshot snapshot, AnalysisRequest request) {
        var focalParticipant = focalParticipant(snapshot, request.focalParticipantId());
        var transitionPolicy = TransitionPolicy.p3();
        var transitions = transitionSelector.select(
                snapshot, request.focalParticipantId(), transitionPolicy);
        var championContext = championContext(snapshot, transitions, focalParticipant);
        var policyVersions = new ArrayList<>(List.of(
                LensAdmissionPolicy.VERSION, DecisionEpisodeComposer.VERSION, ReviewOrderingPolicy.VERSION));
        championContext.version().ifPresent(version -> policyVersions.add("championKnowledgeVersion=" + version));
        var evidenceRevision = revisionFactory.create(
                snapshot,
                transitionPolicy.version(),
                StateReceiptProjector.PROJECTION_RULE_VERSION,
                policyVersions.toArray(String[]::new));
        var revision = new AnalysisRevision(
                evidenceRevision,
                historicalMethodVersions(snapshot),
                transitionPolicy.version(),
                StateReceiptProjector.PROJECTION_RULE_VERSION,
                LensAdmissionPolicy.VERSION,
                DecisionEpisodeComposer.VERSION,
                ReviewOrderingPolicy.VERSION,
                championContext.version());
        var eligibleUnselectedCount = transitionSelector.eligible(
                        snapshot, request.focalParticipantId(), transitionPolicy)
                .size() - transitions.size();
        var episodes = transitions.stream()
                .map(episodeComposer::compose)
                .flatMap(Optional::stream)
                .toList();
        var ordered = reviewOrderingPolicy.order(episodes);
        var review = new Review(
                ordered.episodes(),
                ordered.learningOrder(),
                ordered.chronologicalOrder(),
                ordered.navigation().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                java.util.Map.Entry::getKey,
                                entry -> entry.getValue().temporalCue(),
                                (left, right) -> left,
                                java.util.LinkedHashMap::new)),
                ordered.orderingRuleVersion());
        var arc = new MatchArc(
                transitions,
                transitions.stream().collect(java.util.stream.Collectors.toMap(
                        TransitionPacket::transitionId,
                        packet -> lensAdmissionPolicy.admit(packet,
                                Optional.ofNullable(championContext.lenses()
                                        .get(packet.transitionId()))).primary())),
                eligibleUnselectedCount,
                transitionPolicy.version(),
                transitions.isEmpty()
                        ? Optional.of("NO_ELIGIBLE_TRANSITIONS")
                        : Optional.empty());

        if (request.requestedEvidenceRevision() != null
                && !request.requestedEvidenceRevision().equals(evidenceRevision)) {
            throw MatchAnalysisException.staleRevision(evidenceRevision);
        }

        var active = request.calm()
                ? Optional.<ActiveAnalysis>empty()
                : Optional.of(activeAnalysis(
                        snapshot, request, revision, transitions, episodes, championContext.lenses()));
        return new MatchAnalysis(
                new MatchSummary(snapshot.header(), focalParticipant),
                revision,
                arc,
                review,
                active);
    }

    private ActiveAnalysis activeAnalysis(
            MatchEvidenceSnapshot snapshot,
            AnalysisRequest request,
            AnalysisRevision revision,
            List<TransitionPacket> transitions,
            List<DecisionEpisode> episodes,
            Map<String, ChampionTimingLens> championLenses) {
        var linkedEpisode = episodes.stream()
                .filter(episode -> episode.id().equals(request.selectedObjectId()))
                .findFirst();
        var sourceTransitionId = linkedEpisode
                .map(DecisionEpisode::transitionId)
                .orElse(request.selectedObjectId());
        var packet = transitions.stream()
                .filter(candidate -> candidate.transitionId().equals(sourceTransitionId))
                .findFirst()
                .orElseThrow(MatchAnalysisException::analyticalObjectNotFound);
        if (packet.interval().startMs() != request.intervalStartMs()
                || packet.interval().endMs() != request.intervalEndMs()
                || !packet.questionKind().questionId().equals(request.questionId())) {
            throw MatchAnalysisException.invalidRequest();
        }
        var admission = lensAdmissionPolicy.admit(packet,
                Optional.ofNullable(championLenses.get(packet.transitionId())));
        var selection = lensAdmissionPolicy.select(admission, request.requestedLens());
        var limitations = new TreeSet<>(packet.limitationCodes());
        limitations.addAll(selection.limitations());
        var episodeForTransition = linkedEpisode.or(() -> episodes.stream()
                .filter(episode -> episode.transitionId().equals(packet.transitionId()))
                .findFirst());
        return new ActiveAnalysis(
                new AnalysisContext(
                        request.matchId(),
                        request.focalParticipantId(),
                        request.selectedObjectId(),
                        packet.interval(),
                        packet.questionKind().questionId(),
                        revision.evidenceRevision(),
                        admission.primary(),
                        selection.selected(),
                        admission.available()),
                packet.transitionId(),
                packet.receipt(),
                selection.payload(),
                selection.payload().claims(),
                limitations,
                new EvidenceBundle(
                        revision, packet.evidenceReferences(), snapshot.coverage()),
                supportedQuestions(packet),
                episodeForTransition);
    }

    private ChampionContext championContext(
            MatchEvidenceSnapshot snapshot, List<TransitionPacket> packets, MatchParticipant focal) {
        var lenses = new LinkedHashMap<String, ChampionTimingLens>();
        var usedVersion = Optional.<String>empty();
        var participantIds = snapshot.participants().stream().map(MatchParticipant::participantId).toList();
        var projector = new InventoryProjector();
        for (var packet : packets) {
            if (packet.questionKind() != QuestionKind.CONVERSION) continue;
            var purchases = snapshot.itemTransitions().stream()
                    .filter(item -> Objects.equals(item.actorParticipantId(), focal.participantId()))
                    .filter(item -> item.key().providerEventType().equals("ITEM_PURCHASED"))
                    .filter(item -> Objects.equals(item.itemId(), 6631))
                    .filter(item -> item.key().representedAtMs() >= packet.interval().startMs()
                            && item.key().representedAtMs() <= packet.interval().endMs())
                    .toList();
            for (var purchase : purchases) {
                var beforePurchase = projector.reduce(participantIds, snapshot.itemTransitions(), purchase.key())
                        .inventories().stream()
                        .filter(inventory -> inventory.participantId() == focal.participantId())
                        .findFirst().orElseThrow();
                if (beforePurchase.coverageStatus() != CoverageStatus.RECONSTRUCTED
                        || beforePurchase.itemQuantities().getOrDefault(6631, 0) > 0) continue;
                var participation = packet.anchors().stream()
                        .filter(anchor -> anchor.kind() != AnchorKind.ITEM
                                && anchor.kind() != AnchorKind.PROGRESSION && anchor.kind() != AnchorKind.OTHER)
                        .filter(anchor -> anchor.key().representedAtMs() > purchase.key().representedAtMs())
                        .filter(anchor -> Objects.equals(anchor.actorParticipantId(), focal.participantId())
                                || anchor.assistersObserved()
                                        && anchor.assisterParticipantIds().contains(focal.participantId()))
                        .filter(anchor -> anchor.limitationCodes().isEmpty())
                        .filter(anchor -> snapshot.coverage().stream().anyMatch(coverage ->
                                coverage.signal().equals("item_events")
                                        && coverage.status() == CoverageStatus.OBSERVED
                                        && coverage.representedStartMs() != null
                                        && coverage.representedStartMs() == 0
                                        && coverage.representedEndMs() != null
                                        && coverage.representedEndMs() >= anchor.key().representedAtMs()
                                        && Objects.equals(coverage.sourceCaptureId(), purchase.evidence().sourceCaptureId())))
                        .filter(anchor -> projector.reduce(participantIds, snapshot.itemTransitions(), anchor.key())
                                .inventories().stream().anyMatch(inventory ->
                                        inventory.participantId() == focal.participantId()
                                                && inventory.coverageStatus() == CoverageStatus.RECONSTRUCTED
                                                && inventory.itemQuantities().getOrDefault(6631, 0) > 0))
                        .findFirst();
                if (participation.isEmpty()) continue;
                var assertions = knowledge.findApplicable(new ChampionKnowledgeQuery(
                        snapshot.header().gameVersion(), focal.championId(), purchase.itemId(),
                        purchase.key().representedAtMs(), packet.questionKind()));
                var approved = assertions.stream()
                        .filter(assertion -> assertion.reviewStatus() == ReviewStatus.APPROVED).toList();
                if (approved.isEmpty()) continue;
                var applicableVersion = knowledge.knowledgeVersion();
                if (applicableVersion.isEmpty()) continue;
                usedVersion = applicableVersion;
                var claims = new ArrayList<>(packet.claims());
                var time = purchase.key().representedAtMs();
                var prefix = packet.transitionId() + ":champion:";
                claims.add(new EvidenceClaim(prefix + "ownership",
                        "Stridebreaker became owned at " + String.format(java.util.Locale.ROOT,
                                "%d:%02d.%03d", time / 60_000, time / 1_000 % 60, time % 1_000)
                                + " (item 6631).",
                        AssertionMode.RECONSTRUCTED, purchase.evidenceReferences(), Set.of()));
                approved.forEach(assertion -> claims.add(new EvidenceClaim(
                        prefix + assertion.assertionId(), assertion.summary(), AssertionMode.EXPERT_MAINTAINED,
                        List.of(), Set.of("ACTIVE_USE_REQUIRED", "CHAMPION_HIT_FOR_MOVEMENT"))));
                claims.add(new EvidenceClaim(prefix + "unknown",
                        "Active use, readiness, and causal impact are unknown.",
                        AssertionMode.UNKNOWN, List.of(), Set.of("ACTIVE_USE_UNKNOWN", "READINESS_UNKNOWN", "CAUSAL_IMPACT_UNKNOWN")));
                lenses.put(packet.transitionId(), new ChampionTimingLens(usedVersion.orElseThrow(),
                        approved.stream().map(assertion -> new ChampionTimingLens.Capability(
                                "ITEM", assertion.summary(), assertion)).toList(), claims));
                break;
            }
        }
        return new ChampionContext(Map.copyOf(lenses), usedVersion);
    }

    private record ChampionContext(Map<String, ChampionTimingLens> lenses, Optional<String> version) {}

    private MatchParticipant focalParticipant(
            MatchEvidenceSnapshot snapshot, int focalParticipantId) {
        return snapshot.participants().stream()
                .filter(participant -> participant.participantId() == focalParticipantId)
                .findFirst()
                .orElseThrow(MatchAnalysisException::focalParticipantNotFound);
    }

    private List<ContextualQuestion> supportedQuestions(TransitionPacket packet) {
        return List.of(
                new ContextualQuestion(
                        packet.questionKind().questionId(),
                        "What does the bounded evidence show in this interval?",
                        AssertionMode.OBSERVED),
                new ContextualQuestion(
                        "evidence-limits",
                        "What remains unsupported by this evidence?",
                        AssertionMode.UNKNOWN));
    }

    private List<String> historicalMethodVersions(MatchEvidenceSnapshot snapshot) {
        var versions = new TreeSet<String>();
        snapshot.observations().forEach(observation ->
                versions.add(observation.evidence().methodVersion()));
        snapshot.anchors().forEach(anchor -> anchor.evidenceReferences().forEach(reference ->
                versions.add(reference.methodVersion())));
        snapshot.itemTransitions().forEach(transition ->
                transition.evidenceReferences().forEach(reference ->
                        versions.add(reference.methodVersion())));
        snapshot.coverage().forEach(coverage -> versions.add(coverage.methodVersion()));
        return List.copyOf(versions);
    }
}
