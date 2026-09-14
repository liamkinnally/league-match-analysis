package dev.leagueanalysis.ingestion.riot.domain;

import java.util.List;

/** Detail-derived facts. Nullable counters are retained without assigning rune semantics. */
public record ParticipantDetails(Runes runes, Totals totals, Boolean gameEndedInEarlySurrender,
        Boolean gameEndedInSurrender, Boolean teamEarlySurrendered) {
    public static final String VERSION = "participant-details-v1";
    public static ParticipantDetails empty() { return new ParticipantDetails(null, Totals.empty(), null, null, null); }
    public record Runes(List<Style> styles, Shards shards) {
        public Runes { styles = List.copyOf(styles); }
    }
    public record Style(Integer styleId, String role, List<Selection> selections) {
        public Style { selections = List.copyOf(selections); }
    }
    public record Selection(int runeId, Integer var1, Integer var2, Integer var3) {}
    public record Shards(Integer offense, Integer flex, Integer defense) {}
    public record Totals(Long totalDamageDealt, Long totalDamageDealtToChampions, Long totalHeal,
            Long totalHealsOnTeammates, Long totalDamageShieldedOnTeammates) {
        public static Totals empty() { return new Totals(null, null, null, null, null); }
    }
}
