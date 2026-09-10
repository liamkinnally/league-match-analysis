package dev.leagueanalysis.analysis.death.application;

import dev.leagueanalysis.analysis.death.domain.CoarseMapRegion;
import java.util.Set;

public class CoarseMapProjector {
    public static final String RULE_VERSION = "summoners-rift-grid-v1";

    public Projection project(int mapId, Integer x, Integer y) {
        if (mapId != 11) {
            return unknown("UNSUPPORTED_MAP");
        }
        if (x == null || y == null) {
            return unknown("POSITION_MISSING");
        }
        if (!isInRange(x) || !isInRange(y)) {
            return unknown("POSITION_OUT_OF_RANGE");
        }
        return new Projection(regionFor(y, x), RULE_VERSION, Set.of());
    }

    private Projection unknown(String limitationCode) {
        return new Projection(CoarseMapRegion.UNKNOWN, RULE_VERSION, Set.of(limitationCode));
    }

    private boolean isInRange(int coordinate) {
        return coordinate >= 0 && coordinate <= 15_000;
    }

    private CoarseMapRegion regionFor(int y, int x) {
        return switch (band(y)) {
            case 0 -> switch (band(x)) {
                case 0 -> CoarseMapRegion.SOUTH_WEST;
                case 1 -> CoarseMapRegion.SOUTH_CENTER;
                default -> CoarseMapRegion.SOUTH_EAST;
            };
            case 1 -> switch (band(x)) {
                case 0 -> CoarseMapRegion.CENTER_WEST;
                case 1 -> CoarseMapRegion.CENTER;
                default -> CoarseMapRegion.CENTER_EAST;
            };
            default -> switch (band(x)) {
                case 0 -> CoarseMapRegion.NORTH_WEST;
                case 1 -> CoarseMapRegion.NORTH_CENTER;
                default -> CoarseMapRegion.NORTH_EAST;
            };
        };
    }

    private int band(int coordinate) {
        if (coordinate < 5_000) {
            return 0;
        }
        return coordinate < 10_000 ? 1 : 2;
    }

    public record Projection(
            CoarseMapRegion region, String ruleVersion, Set<String> limitationCodes) {
        public Projection {
            limitationCodes = Set.copyOf(limitationCodes);
        }
    }
}
