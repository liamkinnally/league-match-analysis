package dev.leagueanalysis.ingestion.riot.domain;

import java.util.Objects;

final class DomainText {
    private DomainText() {}

    static String require(String value) {
        var normalized = Objects.requireNonNull(value, "value").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("BLANK_IDENTIFIER");
        }
        return normalized;
    }
}
