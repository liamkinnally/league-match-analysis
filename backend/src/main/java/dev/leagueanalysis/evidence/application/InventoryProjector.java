package dev.leagueanalysis.evidence.application;

import dev.leagueanalysis.evidence.domain.CoverageStatus;
import dev.leagueanalysis.evidence.domain.EvidenceReference;
import dev.leagueanalysis.evidence.domain.InventoryAmbiguity;
import dev.leagueanalysis.evidence.domain.InventorySnapshot;
import dev.leagueanalysis.evidence.domain.ItemTransition;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

public class InventoryProjector {
    public static final String RULE_VERSION = "match-v5-item-reducer-v1";

    public Projection reduce(
            List<Integer> participantIds,
            List<ItemTransition> transitions,
            TimelineEventKey exclusiveUpperBound) {
        Objects.requireNonNull(exclusiveUpperBound, "exclusiveUpperBound");
        return reduceInternal(
                participantIds,
                transitions,
                exclusiveUpperBound.matchId(),
                transition -> !transition.key().matchId().equals(exclusiveUpperBound.matchId())
                        || transition.key().compareTo(exclusiveUpperBound) < 0);
    }

    public Projection reduce(
            List<Integer> participantIds,
            List<ItemTransition> transitions,
            TimelineEventKey exclusiveUpperBound,
            Map<Integer, List<Integer>> observedEndItems) {
        return reduce(
                participantIds,
                transitions,
                transitions,
                exclusiveUpperBound,
                observedEndItems);
    }

    public Projection reduce(
            List<Integer> participantIds,
            List<ItemTransition> transitionsBeforeDeath,
            List<ItemTransition> allTransitions,
            TimelineEventKey exclusiveUpperBound,
            Map<Integer, List<Integer>> observedEndItems) {
        Objects.requireNonNull(exclusiveUpperBound, "exclusiveUpperBound");
        Objects.requireNonNull(observedEndItems, "observedEndItems");
        var atDeath = reduce(participantIds, transitionsBeforeDeath, exclusiveUpperBound);
        var fullMatch = reduceInternal(
                participantIds, allTransitions, exclusiveUpperBound.matchId(), transition -> true);
        var fullByParticipant = new TreeMap<Integer, InventorySnapshot>();
        for (var inventory : fullMatch.inventories()) {
            fullByParticipant.put(inventory.participantId(), inventory);
        }
        var reconciled = atDeath.inventories().stream()
                .map(inventory -> reconcile(
                        inventory, fullByParticipant.get(inventory.participantId()), observedEndItems.get(inventory.participantId())))
                .toList();
        return new Projection(reconciled, fullMatch.ambiguities());
    }

    private Projection reduceInternal(
            List<Integer> participantIds,
            List<ItemTransition> transitions,
            String expectedMatchId,
            Predicate<ItemTransition> include) {
        Objects.requireNonNull(transitions, "transitions");
        var accumulators = accumulatorsFor(participantIds);
        var resultAmbiguities = new ArrayList<InventoryAmbiguity>();
        var ordered = transitions.stream()
                .map(transition -> Objects.requireNonNull(transition, "transition"))
                .filter(include)
                .sorted(transitionOrder())
                .toList();

        for (int index = 0; index < ordered.size();) {
            var key = ordered.get(index).key();
            var groupEnd = index + 1;
            while (groupEnd < ordered.size() && ordered.get(groupEnd).key().equals(key)) {
                groupEnd++;
            }
            var group = ordered.subList(index, groupEnd);
            if (!key.matchId().equals(expectedMatchId)) {
                for (var transition : group) {
                    recordAmbiguity("EVENT_MATCH_MISMATCH", transition, accumulators, resultAmbiguities);
                }
            } else if (group.size() > 1) {
                reconcileLogicalGroup(group, accumulators, resultAmbiguities);
            } else {
                apply(group.getFirst(), accumulators, resultAmbiguities);
            }
            index = groupEnd;
        }

        var inventories = accumulators.values().stream().map(Accumulator::toInventory).toList();
        return new Projection(inventories, resultAmbiguities);
    }

    private InventorySnapshot reconcile(
            InventorySnapshot atDeath,
            InventorySnapshot fullMatch,
            List<Integer> observedEndItems) {
        var observedQuantities = observedQuantities(observedEndItems);
        if (observedQuantities == null) {
            return withLimitation(atDeath, "END_INVENTORY_UNAVAILABLE");
        }
        if (atDeath.coverageStatus() == CoverageStatus.UNKNOWN
                || fullMatch.coverageStatus() == CoverageStatus.UNKNOWN) {
            return atDeath;
        }
        if (!fullMatch.itemQuantities().equals(observedQuantities)) {
            return withLimitation(atDeath, "END_INVENTORY_MISMATCH");
        }
        return atDeath;
    }

    private TreeMap<Integer, Integer> observedQuantities(List<Integer> observedEndItems) {
        if (observedEndItems == null || observedEndItems.size() != 7) {
            return null;
        }
        var quantities = new TreeMap<Integer, Integer>();
        for (var itemId : observedEndItems) {
            if (itemId == null || itemId < 0) {
                return null;
            }
            if (itemId > 0) {
                quantities.merge(itemId, 1, Integer::sum);
            }
        }
        return quantities;
    }

    private InventorySnapshot withLimitation(InventorySnapshot inventory, String limitationCode) {
        var limitations = new TreeSet<>(inventory.limitationCodes());
        limitations.add(limitationCode);
        return new InventorySnapshot(
                inventory.participantId(),
                inventory.itemQuantities(),
                inventory.coverageStatus(),
                inventory.supportingEvidence(),
                inventory.ruleVersion(),
                inventory.ambiguities(),
                limitations);
    }

    private TreeMap<Integer, Accumulator> accumulatorsFor(List<Integer> participantIds) {
        Objects.requireNonNull(participantIds, "participantIds");
        var accumulators = new TreeMap<Integer, Accumulator>();
        for (var participantId : participantIds) {
            if (participantId == null || participantId < 1 || participantId > 10) {
                throw new IllegalArgumentException("INVALID_PARTICIPANT_ID");
            }
            if (accumulators.put(participantId, new Accumulator(participantId)) != null) {
                throw new IllegalArgumentException("DUPLICATE_PARTICIPANT_ID");
            }
        }
        return accumulators;
    }

    private Comparator<ItemTransition> transitionOrder() {
        return Comparator.comparing(ItemTransition::key)
                .thenComparing(transition -> transition.evidence().sourceCaptureId())
                .thenComparing(transition -> transition.evidence().sourceRecordId());
    }

    private void reconcileLogicalGroup(
            List<ItemTransition> group,
            Map<Integer, Accumulator> accumulators,
            List<InventoryAmbiguity> resultAmbiguities) {
        var first = group.getFirst();
        if (group.stream().allMatch(transition -> sameTransition(first, transition))) {
            var evidenceReferences = group.stream()
                    .flatMap(transition -> transition.evidenceReferences().stream())
                    .toList();
            apply(new ItemTransition(
                    first.key(),
                    first.actorParticipantId(),
                    first.itemId(),
                    first.beforeId(),
                    first.afterId(),
                    evidenceReferences), accumulators, resultAmbiguities);
            return;
        }
        for (var transition : group) {
            recordAmbiguity("CONFLICTING_LOGICAL_EVENT", transition, accumulators, resultAmbiguities);
        }
    }

    private boolean sameTransition(ItemTransition left, ItemTransition right) {
        return Objects.equals(left.actorParticipantId(), right.actorParticipantId())
                && Objects.equals(left.itemId(), right.itemId())
                && Objects.equals(left.beforeId(), right.beforeId())
                && Objects.equals(left.afterId(), right.afterId());
    }

    private void apply(
            ItemTransition transition,
            Map<Integer, Accumulator> accumulators,
            List<InventoryAmbiguity> resultAmbiguities) {
        var accumulator = transition.actorParticipantId() == null
                ? null
                : accumulators.get(transition.actorParticipantId());
        if (accumulator == null) {
            recordAmbiguity("UNKNOWN_PARTICIPANT", transition, accumulators, resultAmbiguities);
            return;
        }

        var code = validateTransition(transition);
        if (code != null) {
            recordAmbiguity(code, transition, accumulators, resultAmbiguities);
            return;
        }

        var type = transition.key().providerEventType();
        if (type.equals("ITEM_PURCHASED")) {
            accumulator.add(transition.itemId());
        } else if (type.equals("ITEM_UNDO") && transition.beforeId() == 0) {
            accumulator.add(transition.afterId());
        } else {
            var removedId = type.equals("ITEM_UNDO") ? transition.beforeId() : transition.itemId();
            if (!accumulator.remove(removedId)) {
                recordAmbiguity("ITEM_NOT_PRESENT", transition, accumulators, resultAmbiguities);
                return;
            }
        }
        accumulator.supportingEvidence.addAll(transition.evidenceReferences());
    }

    private String validateTransition(ItemTransition transition) {
        var type = transition.key().providerEventType();
        if (type.equals("ITEM_UNDO")) {
            if (transition.itemId() != null) {
                return "CONTRADICTORY_ITEM_IDS";
            }
            var purchaseUndo = positive(transition.beforeId()) && zero(transition.afterId());
            var saleUndo = zero(transition.beforeId()) && positive(transition.afterId());
            return purchaseUndo || saleUndo ? null : "INVALID_UNDO_SHAPE";
        }
        if (!Set.of("ITEM_PURCHASED", "ITEM_SOLD", "ITEM_DESTROYED").contains(type)) {
            return "UNSUPPORTED_ITEM_TRANSITION";
        }
        if (transition.beforeId() != null || transition.afterId() != null) {
            return "CONTRADICTORY_ITEM_IDS";
        }
        return positive(transition.itemId()) ? null : "INVALID_ITEM_ID";
    }

    private boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private boolean zero(Integer value) {
        return value != null && value == 0;
    }

    private void recordAmbiguity(
            String code,
            ItemTransition transition,
            Map<Integer, Accumulator> accumulators,
            List<InventoryAmbiguity> resultAmbiguities) {
        var ambiguity = new InventoryAmbiguity(code, transition);
        resultAmbiguities.add(ambiguity);
        var accumulator = transition.actorParticipantId() == null
                ? null
                : accumulators.get(transition.actorParticipantId());
        if (accumulator != null) {
            accumulator.ambiguities.add(ambiguity);
            accumulator.limitationCodes.add(code);
        }
    }

    public record Projection(List<InventorySnapshot> inventories, List<InventoryAmbiguity> ambiguities) {
        public Projection {
            inventories = List.copyOf(Objects.requireNonNull(inventories, "inventories"));
            ambiguities = List.copyOf(Objects.requireNonNull(ambiguities, "ambiguities"));
        }
    }

    private static final class Accumulator {
        private final int participantId;
        private final TreeMap<Integer, Integer> quantities = new TreeMap<>();
        private final List<EvidenceReference> supportingEvidence = new ArrayList<>();
        private final List<InventoryAmbiguity> ambiguities = new ArrayList<>();
        private final Set<String> limitationCodes = new HashSet<>();

        private Accumulator(int participantId) {
            this.participantId = participantId;
        }

        private void add(int itemId) {
            quantities.merge(itemId, 1, Integer::sum);
        }

        private boolean remove(int itemId) {
            var quantity = quantities.get(itemId);
            if (quantity == null) {
                return false;
            }
            if (quantity == 1) {
                quantities.remove(itemId);
            } else {
                quantities.put(itemId, quantity - 1);
            }
            return true;
        }

        private InventorySnapshot toInventory() {
            return new InventorySnapshot(
                    participantId,
                    quantities,
                    ambiguities.isEmpty() ? CoverageStatus.RECONSTRUCTED : CoverageStatus.UNKNOWN,
                    supportingEvidence,
                    RULE_VERSION,
                    ambiguities,
                    limitationCodes);
        }
    }
}
