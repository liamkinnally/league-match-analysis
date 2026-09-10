package dev.leagueanalysis.ingestion.riot.application;

public record RiotIngestionCommand(String gameName, String tagLine, int matchLimit) {
    public RiotIngestionCommand {
        gameName = gameName == null ? "" : gameName.strip();
        tagLine = tagLine == null ? "" : tagLine.strip();
        if (gameName.isEmpty() || gameName.length() > 64) {
            throw new IllegalArgumentException("INVALID_GAME_NAME");
        }
        if (tagLine.isEmpty() || tagLine.length() > 16) {
            throw new IllegalArgumentException("INVALID_TAG_LINE");
        }
        if (matchLimit < 1 || matchLimit > 20) {
            throw new IllegalArgumentException("INVALID_MATCH_LIMIT");
        }
    }
}
