package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.analysis.match.domain.ParticipantRunes;
import dev.leagueanalysis.ingestion.riot.domain.ParticipantDetails;
import java.util.List;

/** Projects source counters without assigning patch-dependent analytical meaning. */
public final class RunePerformanceMapper {
    private RunePerformanceMapper() {}

    public static ParticipantRunes project(ParticipantDetails.Runes snapshot, String gameVersion) {
        var parts = gameVersion == null ? new String[0] : gameVersion.split("\\.");
        var patch = parts.length >= 2 && parts[0].matches("[0-9]+") && parts[1].matches("[0-9]+")
                ? parts[0] + "." + parts[1] : "unknown";
        var layout = patch.equals("unknown") ? null : "rune-layout-" + patch + "-v1";
        if (snapshot == null) return new ParticipantRunes("missing", patch, ParticipantDetails.VERSION,
                layout, "missing", List.of(), new ParticipantRunes.Shards(null, null, null));
        var styles = snapshot.styles().stream().map(style -> new ParticipantRunes.Style(
                style.styleId(), style.role(), style.selections().stream().map(selection ->
                    new ParticipantRunes.Selection(selection.runeId(), List.of(),
                            new ParticipantRunes.Counters(selection.var1(), selection.var2(), selection.var3())))
                        .toList())).toList();
        var shards = snapshot.shards();
        return new ParticipantRunes("available", patch, ParticipantDetails.VERSION, layout, "unverified", styles,
                new ParticipantRunes.Shards(shards.offense(), shards.flex(), shards.defense()));
    }
}
