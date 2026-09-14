package dev.leagueanalysis.analysis.match.application;

import dev.leagueanalysis.ingestion.riot.domain.ParticipantDetails;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class RunePerformanceMapperTest {
    @ParameterizedTest
    @ValueSource(strings = {"16.17", "16.18", "99.42"})
    void preservesIndividualCountersForHistoricalCurrentAndFuturePatches(String patch) throws Exception {
        var view = RunePerformanceMapper.project(runes(8437, 576, 454, 0), patch + ".810.4348");
        assertThat(view.matchPatch()).isEqualTo(patch);
        assertThat(view.layoutManifestId()).isEqualTo("rune-layout-" + patch + "-v1");
        assertThat(view.performanceStatus()).isEqualTo("unverified");
        var selection = JsonMapper.builder().build().valueToTree(view).path("styles").get(0).path("selections").get(0);
        assertThat(selection.path("counters").path("var1").asInt()).isEqualTo(576);
        assertThat(selection.path("counters").path("var2").asInt()).isEqualTo(454);
        assertThat(selection.path("counters").path("var3").isIntegralNumber()).isTrue();
        assertThat(selection.path("counters").path("var3").asInt()).isZero();
        assertThat(selection.path("metrics").isEmpty()).isTrue();
    }
    @Test void preservesNullAndNegativeSourceCountersWithoutInventingDisplayMeaning() throws Exception {
        var view = RunePerformanceMapper.project(runes(999999, null, -1, 0), "27.1.1");
        var selection = JsonMapper.builder().build().valueToTree(view).path("styles").get(0).path("selections").get(0);
        assertThat(selection.path("runeId").asInt()).isEqualTo(999999);
        assertThat(selection.path("counters").path("var1").isNull()).isTrue();
        assertThat(selection.path("counters").path("var2").asInt()).isEqualTo(-1);
        assertThat(selection.path("counters").path("var3").asInt()).isZero();
        assertThat(view.styles().getFirst().selections().getFirst().metrics()).isEmpty();
    }
    @Test void preservesMissingSnapshotAndRejectsMalformedPatchIdentity() {
        var missing = RunePerformanceMapper.project(null, "16.18.1");
        assertThat(missing.availability()).isEqualTo("missing");
        assertThat(missing.performanceStatus()).isEqualTo("missing");
        assertThat(missing.styles()).isEmpty();
        assertThat(RunePerformanceMapper.project(runes(8437, 1, 2, 3), "invalid").layoutManifestId()).isNull();
    }
    private static ParticipantDetails.Runes runes(int id, Integer a, Integer b, Integer c) {
        return new ParticipantDetails.Runes(List.of(new ParticipantDetails.Style(8400, "primaryStyle", List.of(new ParticipantDetails.Selection(id, a, b, c)))), new ParticipantDetails.Shards(5008, 5008, null));
    }
}
