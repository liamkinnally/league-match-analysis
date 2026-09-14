package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import dev.leagueanalysis.ingestion.riot.domain.ParticipantDetails;
import java.util.ArrayList;
import tools.jackson.databind.JsonNode;

/** Optional detail fields must never invalidate an otherwise usable match. */
public final class ParticipantDetailsDecoder {
    private ParticipantDetailsDecoder() {}
    public static ParticipantDetails decode(JsonNode node) {
        var perks = node.path("perks");
        ParticipantDetails.Runes runes = null;
        if (perks.isObject()) {
            var styles = new ArrayList<ParticipantDetails.Style>();
            var rawStyles = perks.path("styles");
            if (rawStyles.isArray() && rawStyles.size() <= 10) for (var style : rawStyles) {
                if (!style.isObject()) continue;
                var selections = new ArrayList<ParticipantDetails.Selection>();
                var rawSelections = style.path("selections");
                if (rawSelections.isArray() && rawSelections.size() <= 20) for (var selection : rawSelections) {
                    var id = positiveInt(selection.path("perk"));
                    if (id != null) selections.add(new ParticipantDetails.Selection(id,
                            integer(selection.path("var1")), integer(selection.path("var2")), integer(selection.path("var3"))));
                }
                var role = style.path("description").asText("");
                if (!role.equals("primaryStyle") && !role.equals("subStyle")) role = "unknown";
                styles.add(new ParticipantDetails.Style(positiveInt(style.path("style")), role, selections));
            }
            var shards = perks.path("statPerks");
            runes = new ParticipantDetails.Runes(styles, new ParticipantDetails.Shards(
                    positiveInt(shards.path("offense")), positiveInt(shards.path("flex")), positiveInt(shards.path("defense"))));
        }
        return new ParticipantDetails(runes, new ParticipantDetails.Totals(
                count(node.path("totalDamageDealt")), count(node.path("totalDamageDealtToChampions")),
                count(node.path("totalHeal")), count(node.path("totalHealsOnTeammates")),
                count(node.path("totalDamageShieldedOnTeammates"))), bool(node.path("gameEndedInEarlySurrender")),
                bool(node.path("gameEndedInSurrender")), bool(node.path("teamEarlySurrendered")));
    }
    private static Integer integer(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() ? value.intValue() : null;
    }
    private static Integer positiveInt(JsonNode value) {
        var n = integer(value); return n != null && n > 0 ? n : null;
    }
    private static Long count(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0
                && value.longValue() <= 9_007_199_254_740_991L ? value.longValue() : null;
    }
    private static Boolean bool(JsonNode value) { return value.isBoolean() ? value.booleanValue() : null; }
}
