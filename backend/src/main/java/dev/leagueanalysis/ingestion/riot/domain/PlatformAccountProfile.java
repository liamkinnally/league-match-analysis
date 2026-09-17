package dev.leagueanalysis.ingestion.riot.domain;

import java.time.Instant;

/** Summoner-v4 evidence that the resolved account exists on the selected platform. */
public record PlatformAccountProfile(int profileIconId, Long summonerLevel, Instant revisionAt) {}
