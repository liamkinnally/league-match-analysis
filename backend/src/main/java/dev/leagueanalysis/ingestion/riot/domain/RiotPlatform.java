package dev.leagueanalysis.ingestion.riot.domain;

import java.util.Locale;

/** Publicly supported platforms and their Account-v1/Match-v5 regional hosts. */
public enum RiotPlatform {
    NA1("AMERICAS"), EUW1("EUROPE"), EUN1("EUROPE"), KR("ASIA");

    private final String regionalRoute;
    RiotPlatform(String regionalRoute) { this.regionalRoute = regionalRoute; }
    public String regionalRoute() { return regionalRoute; }
    public static RiotPlatform parse(String value) {
        if (value == null || value.isBlank()) return NA1;
        try { return valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("UNSUPPORTED_PLATFORM"); }
    }
    public static boolean supported(String value) {
        if (value == null) return false;
        try { return valueOf(value) != null; } catch (IllegalArgumentException invalid) { return false; }
    }
    public static RiotPlatform fromMatchId(String id) {
        if (id == null || !id.matches("(NA1|EUW1|EUN1|KR)_[0-9]{1,20}"))
            throw new IllegalArgumentException("INVALID_MATCH_ID");
        return valueOf(id.substring(0, id.indexOf('_')));
    }
}
