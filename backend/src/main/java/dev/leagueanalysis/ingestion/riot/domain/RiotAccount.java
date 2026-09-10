package dev.leagueanalysis.ingestion.riot.domain;

public record RiotAccount(String puuid, String gameName, String tagLine) {
    public RiotAccount {
        puuid = DomainText.require(puuid);
        gameName = DomainText.require(gameName);
        tagLine = DomainText.require(tagLine);
    }
}
