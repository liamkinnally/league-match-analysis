package dev.leagueanalysis.ingestion.riot.application;

import java.time.Instant;
import java.util.UUID;

/** Public state of an optional timeline; detail statistics stay available independently. */
public record TimelineLookup(String matchId, UUID runId, String status, String message, Instant retryNotBefore) {}
