package dev.leagueanalysis.ingestion.riot.application;

public enum RiotFailureCode {
    CONFIGURATION_MISSING,
    INVALID_INPUT,
    BODY_TOO_LARGE,
    AUTHENTICATION_FAILED,
    NOT_FOUND,
    RATE_LIMITED,
    UPSTREAM_UNAVAILABLE,
    UPSTREAM_REJECTED,
    NETWORK_FAILURE,
    INVALID_RESPONSE
}
