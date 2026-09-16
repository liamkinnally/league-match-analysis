package dev.leagueanalysis.ingestion.riot.application;

public record RiotIngestionCommand(String gameName, String tagLine, int matchLimit,
        int queueId, int start, Long endTime, java.util.UUID previousRunId, String platform) {
    public static final java.util.Set<Integer> SUPPORTED_QUEUES = java.util.Set.of(400, 420, 430, 440, 450, 480, 490);

    public static boolean supportsMatch(int queueId, int mapId) {
        return queueId == 450 ? mapId == 12 || mapId == 14
                : SUPPORTED_QUEUES.contains(queueId) && mapId == 11;
    }

    public RiotIngestionCommand(String gameName, String tagLine, int matchLimit) {
        this(gameName, tagLine, matchLimit, 420, 0, null, null);
    }
    public RiotIngestionCommand(String gameName, String tagLine, int matchLimit, int queueId, int start, Long endTime, java.util.UUID previousRunId) {
        this(gameName, tagLine, matchLimit, queueId, start, endTime, previousRunId, "NA1");
    }
    public RiotIngestionCommand {
        platform = dev.leagueanalysis.ingestion.riot.domain.RiotPlatform.parse(platform).name();
        if ((queueId != 0 && !SUPPORTED_QUEUES.contains(queueId)) || start < 0 || start > Integer.MAX_VALUE - 20
                || (endTime != null && endTime < 0)) {
            throw new IllegalArgumentException("INVALID_HISTORY_PAGE");
        }
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
