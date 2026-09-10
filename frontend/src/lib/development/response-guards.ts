import type {
  DemoMatch,
  MatchDevelopment,
  MatchDevelopmentEvent,
  MatchDevelopmentParticipant,
  MatchDevelopmentSample,
  MatchDevelopmentSummary,
  MatchDevelopmentWindow,
  CurrentRanks,
} from "./types";

type UnknownRecord = Record<string, unknown>;

const isRecord = (value: unknown): value is UnknownRecord =>
  typeof value === "object" && value !== null && !Array.isArray(value);
const isString = (value: unknown): value is string =>
  typeof value === "string" && value.length > 0;
const isBoolean = (value: unknown): value is boolean =>
  typeof value === "boolean";
const isInteger = (value: unknown): value is number =>
  Number.isSafeInteger(value);
const isIntegerArray = (value: unknown): value is number[] =>
  Array.isArray(value) && value.every(isInteger);

function isSummary(value: unknown): value is MatchDevelopmentSummary {
  return (
    isRecord(value) &&
    isInteger(value.queueId) &&
    isInteger(value.mapId) &&
    isString(value.gameMode) &&
    isString(value.gameVersion) &&
    isInteger(value.gameCreationMs) &&
    isInteger(value.durationMs) &&
    isInteger(value.focusParticipantId) &&
    isMaybeInteger(value.compareParticipantId) &&
    isBoolean(value.win) &&
    isInteger(value.kills) &&
    isInteger(value.deaths) &&
    isInteger(value.assists) &&
    isInteger(value.totalCs) &&
    isInteger(value.goldEarned)
  );
}

const isMaybeInteger = (value: unknown): value is number | null =>
  value === null || isInteger(value);
const isOptionalText = (value: unknown) =>
  value === undefined || value === null || typeof value === "string";

function isParticipant(value: unknown): value is MatchDevelopmentParticipant {
  return (
    isRecord(value) &&
    isInteger(value.participantId) &&
    isInteger(value.teamId) &&
    isInteger(value.championId) &&
    isString(value.championName) &&
    isString(value.teamPosition) &&
    isBoolean(value.win) &&
    isInteger(value.kills) &&
    isInteger(value.deaths) &&
    isInteger(value.assists) &&
    isInteger(value.laneCs) &&
    isInteger(value.jungleCs) &&
    isInteger(value.totalCs) &&
    isInteger(value.goldEarned) &&
    isInteger(value.goldSpent) &&
    isInteger(value.visionScore) &&
    isInteger(value.summonerSpellOneId) &&
    isInteger(value.summonerSpellTwoId) &&
    isIntegerArray(value.endItemIds)
  );
}

function isSample(value: unknown): value is MatchDevelopmentSample {
  return (
    isRecord(value) &&
    isInteger(value.timestampMs) &&
    isMaybeInteger(value.goldDifference) &&
    isMaybeInteger(value.csDifference) &&
    isMaybeInteger(value.xpDifference) &&
    isMaybeInteger(value.focalLevel) &&
    isMaybeInteger(value.compareLevel) &&
    isMaybeInteger(value.focalTotalGold) &&
    isMaybeInteger(value.focalCs) &&
    isMaybeInteger(value.focalXp)
  );
}

function isEvent(value: unknown): value is MatchDevelopmentEvent {
  return (
    isRecord(value) &&
    isInteger(value.timestampMs) &&
    isString(value.label) &&
    isIntegerArray(value.participantIds) &&
    isMaybeInteger(value.itemId) &&
    isMaybeInteger(value.actorParticipantId) &&
    isMaybeInteger(value.targetParticipantId) &&
    isIntegerArray(value.assisterParticipantIds) &&
    typeof value.assistersObserved === "boolean"
  );
}

function isWindow(value: unknown): value is MatchDevelopmentWindow {
  return (
    isRecord(value) &&
    isString(value.id) &&
    isInteger(value.startMs) &&
    isInteger(value.endMs) &&
    isSample(value.before) &&
    isSample(value.after) &&
    isString(value.summary)
  );
}

export function parseMatchDevelopment(value: unknown): MatchDevelopment {
  if (
    !isRecord(value) ||
    !isString(value.matchId) ||
    !isSummary(value.summary) ||
    !Array.isArray(value.roster) ||
    !value.roster.every(isParticipant) ||
    !isBoolean(value.timelineAvailable) ||
    !Array.isArray(value.samples) ||
    !value.samples.every(isSample) ||
    !Array.isArray(value.windows) ||
    !value.windows.every(isWindow) ||
    !Array.isArray(value.suggestedWindows) ||
    !value.suggestedWindows.every(isWindow) ||
    !Array.isArray(value.events) ||
    !value.events.every(isEvent) ||
    value.roster.some(
      (p) => ![p.gameName, p.tagLine, p.summonerName].every(isOptionalText),
    ) ||
    value.events.some(
      (e) =>
        (e.type !== undefined && !isString(e.type)) ||
        (e.frameAtMs !== undefined && !isInteger(e.frameAtMs)) ||
        (e.frameEventIndex !== undefined && !isInteger(e.frameEventIndex)) ||
        (e.fields !== undefined && !isRecord(e.fields)) ||
        (e.x !== undefined && !isMaybeInteger(e.x)) ||
        (e.y !== undefined && !isMaybeInteger(e.y)),
    ) ||
    (value.teams !== undefined &&
      (!Array.isArray(value.teams) ||
        !value.teams.every(
          (team) =>
            isRecord(team) &&
            isInteger(team.teamId) &&
            (team.win === null || isBoolean(team.win)) &&
            [team.kills, team.deaths, team.assists, team.goldEarned].every(
              isMaybeInteger,
            ) &&
            isRecord(team.objectives) &&
            Object.values(team.objectives).every(isMaybeInteger),
        )))
  ) {
    throw new Error("INVALID_MATCH_DEVELOPMENT_RESPONSE");
  }
  return value as unknown as MatchDevelopment;
}

export function parseCurrentRanks(value: unknown): CurrentRanks {
  if (
    !isRecord(value) ||
    !isString(value.matchId) ||
    ![null, "RANKED_SOLO_5x5", "RANKED_FLEX_SR"].includes(
      value.queueType as string | null,
    ) ||
    !isBoolean(value.refreshing) ||
    !Array.isArray(value.players) ||
    !value.players.every(
      (p) =>
        isRecord(p) &&
        isInteger(p.participantId) &&
        ["loading", "ranked", "unranked", "unavailable"].includes(
          p.status as string,
        ) &&
        [p.tier, p.division, p.fetchedAt, p.error].every(isOptionalText) &&
        (p.leaguePoints === undefined || isMaybeInteger(p.leaguePoints)) &&
        (p.cached === undefined || isBoolean(p.cached)) &&
        (p.stale === undefined || isBoolean(p.stale)),
    ) ||
    new Set(value.players.map((p) => p.participantId)).size !==
      value.players.length
  ) {
    throw new Error("INVALID_CURRENT_RANKS_RESPONSE");
  }
  return value as CurrentRanks;
}

export function parseDemoMatch(value: unknown): DemoMatch {
  if (
    !isRecord(value) ||
    !isString(value.matchId) ||
    !isInteger(value.focusParticipantId) ||
    !isInteger(value.compareParticipantId) ||
    !isBoolean(value.invented)
  ) {
    throw new Error("INVALID_DEMO_MATCH_RESPONSE");
  }
  return value as unknown as DemoMatch;
}
