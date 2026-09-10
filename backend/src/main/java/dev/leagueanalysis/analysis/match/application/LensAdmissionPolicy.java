package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.ChampionTimingLens;
import dev.leagueanalysis.analysis.match.domain.EvidenceClaim;
import dev.leagueanalysis.analysis.match.domain.LensKind;
import dev.leagueanalysis.analysis.match.domain.LensPayload;
import dev.leagueanalysis.analysis.match.domain.MapLens;
import dev.leagueanalysis.analysis.match.domain.MatchAnchor;
import dev.leagueanalysis.analysis.match.domain.QuestionKind;
import dev.leagueanalysis.analysis.match.domain.ReceiptLens;
import dev.leagueanalysis.analysis.match.domain.SequenceLens;
import dev.leagueanalysis.analysis.match.domain.SequenceLens.Band;
import dev.leagueanalysis.analysis.match.domain.SequenceLens.Relation;
import dev.leagueanalysis.analysis.match.domain.SequenceLens.RelationKind;
import dev.leagueanalysis.analysis.match.domain.StateLens;
import dev.leagueanalysis.analysis.match.domain.TransferLens;
import dev.leagueanalysis.analysis.match.domain.TransitionPacket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public final class LensAdmissionPolicy {
    public static final String VERSION = "lens-admission-p3-v1";
    private static final List<LensKind> DISPLAY_ORDER = List.of(
            LensKind.MAP,
            LensKind.SEQUENCE,
            LensKind.STATE,
            LensKind.CHAMPION_TIMING,
            LensKind.TRANSFER,
            LensKind.RECEIPT);

    public Admission admit(
            TransitionPacket packet, Optional<ChampionTimingLens> championTiming) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(championTiming, "championTiming");
        var claims = List.copyOf(packet.claims());
        var payloads = new EnumMap<LensKind, LensPayload>(LensKind.class);

        mapPayload(packet, claims).ifPresent(payload -> payloads.put(payload.kind(), payload));
        sequencePayload(packet, claims)
                .ifPresent(payload -> payloads.put(payload.kind(), payload));
        if (packet.receipt().hasUsableBrackets()) {
            payloads.put(LensKind.STATE, new StateLens(packet.receipt(), claims));
        }
        championTiming.filter(payload -> payload.capabilities().stream()
                        .allMatch(capability -> capability.assertion().reviewStatus()
                                == dev.leagueanalysis.analysis.match.domain.ReviewStatus.APPROVED))
                .ifPresent(payload -> payloads.put(LensKind.CHAMPION_TIMING, payload));
        transferPayload(packet, claims)
                .ifPresent(payload -> payloads.put(payload.kind(), payload));
        payloads.put(LensKind.RECEIPT, new ReceiptLens(packet.receipt(), claims));

        var available = DISPLAY_ORDER.stream().filter(payloads::containsKey).toList();
        return new Admission(primary(packet.questionKind(), available), available, payloads);
    }

    public Selection select(Admission admission, LensKind requested) {
        Objects.requireNonNull(admission, "admission");
        if (requested == null) {
            return selected(admission, admission.primary(), Set.of());
        }
        if (admission.available().contains(requested)) {
            return selected(admission, requested, Set.of());
        }
        return selected(
                admission, LensKind.RECEIPT, Set.of("REQUESTED_LENS_UNAVAILABLE"));
    }

    private Selection selected(
            Admission admission, LensKind selected, Set<String> limitations) {
        return new Selection(
                selected,
                admission.payloads().get(selected),
                limitations);
    }

    private Optional<MapLens> mapPayload(
            TransitionPacket packet, List<EvidenceClaim> claims) {
        var points = packet.anchors().stream()
                .filter(anchor -> anchor.positionX() != null && anchor.positionY() != null)
                .map(anchor -> new MapLens.Point(
                        anchor.key().representedAtMs(),
                        anchor.positionX(),
                        anchor.positionY(),
                        anchor.kind(),
                        anchor.actorParticipantId(),
                        anchor.descriptor(),
                        anchor.evidenceReferences()))
                .toList();
        return points.isEmpty() ? Optional.empty() : Optional.of(new MapLens(points, claims));
    }

    private Optional<SequenceLens> sequencePayload(
            TransitionPacket packet, List<EvidenceClaim> claims) {
        if (packet.anchors().size() < 2) {
            return Optional.empty();
        }
        var bands = new ArrayList<Band>();
        int index = 0;
        while (index < packet.anchors().size()) {
            var representedAtMs = packet.anchors().get(index).key().representedAtMs();
            var sameTime = new ArrayList<MatchAnchor>();
            while (index < packet.anchors().size()
                    && packet.anchors().get(index).key().representedAtMs() == representedAtMs) {
                sameTime.add(packet.anchors().get(index));
                index++;
            }
            bands.add(new Band(
                    "band-" + (bands.size() + 1), representedAtMs, representedAtMs,
                    sameTime, sameTime.size() > 1));
        }
        var relations = new ArrayList<Relation>();
        for (int relationIndex = 0; relationIndex < bands.size(); relationIndex++) {
            var band = bands.get(relationIndex);
            if (band.parallel()) {
                relations.add(new Relation(
                        band.id(), band.id(), RelationKind.CO_OCCURS,
                        band.anchors().stream()
                                .flatMap(anchor -> anchor.evidenceReferences().stream())
                                .distinct()
                                .toList()));
            }
            if (relationIndex + 1 < bands.size()) {
                var next = bands.get(relationIndex + 1);
                relations.add(new Relation(
                        band.id(), next.id(), RelationKind.BEFORE,
                        List.of()));
            }
        }
        return Optional.of(new SequenceLens(bands, relations, claims));
    }

    private Optional<TransferLens> transferPayload(
            TransitionPacket packet, List<EvidenceClaim> claims) {
        if (packet.questionKind() != QuestionKind.OVERLAPPING_EXCHANGE) {
            return Optional.empty();
        }
        var rows = packet.anchors().stream()
                .map(anchor -> new TransferLens.Row(
                        anchor.key().representedAtMs(),
                        anchor.kind().name(),
                        "OBSERVED",
                        anchor.evidenceReferences()))
                .toList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(new TransferLens(rows, claims));
    }

    private LensKind primary(QuestionKind questionKind, List<LensKind> available) {
        var preferred = switch (questionKind) {
            case MIXED_VALUE -> List.of(LensKind.MAP, LensKind.SEQUENCE, LensKind.STATE);
            case CONVERSION, OVERLAPPING_EXCHANGE -> List.of(
                    LensKind.SEQUENCE, LensKind.STATE, LensKind.MAP);
            case ADVERSE_CONSEQUENCE -> List.of(
                    LensKind.STATE, LensKind.SEQUENCE, LensKind.MAP);
        };
        return preferred.stream()
                .filter(available::contains)
                .findFirst()
                .orElse(LensKind.RECEIPT);
    }

    public record Admission(
            LensKind primary,
            List<LensKind> available,
            Map<LensKind, LensPayload> payloads) {
        public Admission {
            primary = Objects.requireNonNull(primary, "primary");
            available = List.copyOf(Objects.requireNonNull(available, "available"));
            payloads = Collections.unmodifiableMap(
                    new EnumMap<>(Objects.requireNonNull(payloads, "payloads")));
            if (!available.contains(LensKind.RECEIPT)
                    || !available.contains(primary)
                    || !payloads.keySet().containsAll(available)) {
                throw new IllegalArgumentException("INVALID_LENS_ADMISSION");
            }
        }
    }

    public record Selection(
            LensKind selected,
            LensPayload payload,
            Set<String> limitations) {
        public Selection {
            selected = Objects.requireNonNull(selected, "selected");
            payload = Objects.requireNonNull(payload, "payload");
            var ordered = new TreeSet<>(Objects.requireNonNull(limitations, "limitations"));
            if (ordered.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("INVALID_LENS_LIMITATION");
            }
            limitations = Collections.unmodifiableSortedSet(ordered);
        }
    }
}
