package dev.leagueanalysis.analysis.death.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.death.domain.CoarseMapRegion;
import org.junit.jupiter.api.Test;

class CoarseMapProjectorTest {
    private final CoarseMapProjector projector = new CoarseMapProjector();

    @Test
    void projectsInclusiveGridBoundariesWithoutSemanticLabels() {
        assertProjection(0, 0, CoarseMapRegion.SOUTH_WEST);
        assertProjection(4_999, 4_999, CoarseMapRegion.SOUTH_WEST);
        assertProjection(5_000, 5_000, CoarseMapRegion.CENTER);
        assertProjection(9_999, 9_999, CoarseMapRegion.CENTER);
        assertProjection(10_000, 10_000, CoarseMapRegion.NORTH_EAST);
        assertProjection(15_000, 15_000, CoarseMapRegion.NORTH_EAST);
    }

    @Test
    void projectsEachAxisIndependentlyAcrossTheNineGridCells() {
        assertProjection(5_000, 0, CoarseMapRegion.SOUTH_CENTER);
        assertProjection(10_000, 0, CoarseMapRegion.SOUTH_EAST);
        assertProjection(0, 5_000, CoarseMapRegion.CENTER_WEST);
        assertProjection(10_000, 5_000, CoarseMapRegion.CENTER_EAST);
        assertProjection(0, 10_000, CoarseMapRegion.NORTH_WEST);
        assertProjection(5_000, 10_000, CoarseMapRegion.NORTH_CENTER);
        assertProjection(9_999, 0, CoarseMapRegion.SOUTH_CENTER);
        assertProjection(0, 9_999, CoarseMapRegion.CENTER_WEST);
    }

    @Test
    void retainsUnknownWhenTheXCoordinateIsMissing() {
        var projection = projector.project(11, null, 5_000);

        assertThat(projection.region()).isEqualTo(CoarseMapRegion.UNKNOWN);
        assertThat(projection.limitationCodes()).containsExactly("POSITION_MISSING");
        assertThat(projection.ruleVersion()).isEqualTo("summoners-rift-grid-v1");
    }

    @Test
    void retainsUnknownWhenTheYCoordinateIsMissing() {
        var projection = projector.project(11, 5_000, null);

        assertThat(projection.region()).isEqualTo(CoarseMapRegion.UNKNOWN);
        assertThat(projection.limitationCodes()).containsExactly("POSITION_MISSING");
        assertThat(projection.ruleVersion()).isEqualTo("summoners-rift-grid-v1");
    }

    @Test
    void retainsUnknownForCoordinatesOutsideTheSupportedMapRangeOnEitherAxis() {
        assertUnknown(-1, 0);
        assertUnknown(0, -1);
        assertUnknown(15_001, 0);
        assertUnknown(0, 15_001);
    }

    private void assertUnknown(int x, int y) {
        var projection = projector.project(11, x, y);

        assertThat(projection.region()).isEqualTo(CoarseMapRegion.UNKNOWN);
        assertThat(projection.limitationCodes()).containsExactly("POSITION_OUT_OF_RANGE");
        assertThat(projection.ruleVersion()).isEqualTo("summoners-rift-grid-v1");
    }

    @Test
    void retainsUnknownForUnsupportedMaps() {
        var projection = projector.project(12, 0, 0);

        assertThat(projection.region()).isEqualTo(CoarseMapRegion.UNKNOWN);
        assertThat(projection.limitationCodes()).containsExactly("UNSUPPORTED_MAP");
        assertThat(projection.ruleVersion()).isEqualTo("summoners-rift-grid-v1");
    }

    private void assertProjection(int x, int y, CoarseMapRegion expectedRegion) {
        var projection = projector.project(11, x, y);

        assertThat(projection.region()).isEqualTo(expectedRegion);
        assertThat(projection.limitationCodes()).isEmpty();
        assertThat(projection.ruleVersion()).isEqualTo("summoners-rift-grid-v1");
    }
}
