package dev.leagueanalysis.evidence.domain;

import java.util.Objects;

public record InventoryAmbiguity(String code, ItemTransition transition) {
    public InventoryAmbiguity {
        code = TimelineEventKey.requireText(code);
        transition = Objects.requireNonNull(transition, "transition");
    }
}
