package dev.leagueanalysis.ingestion.riot.domain;

public record RiotId(String gameName, String tagLine) {
    public RiotId {
        gameName = DomainText.require(gameName);
        tagLine = DomainText.require(tagLine);
    }
}
