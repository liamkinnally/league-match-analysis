import "server-only";

import type { MatchDevelopment } from "../lib/development/types";
import type { MatchSummary } from "../lib/player-lookup/types";
import { projectSampleDevelopment } from "../preview/projection";
import { sampleGames } from "./games";

export function getSampleDevelopment(id: string, focus: number, compare?: number): MatchDevelopment | undefined {
  const game = sampleGames.find(game => game.id === id);
  return game && projectSampleDevelopment(game.source, id, focus, compare);
}

export function getSampleHistory(): MatchSummary[] {
  return sampleGames.map(({ id, focus, source }) => {
    const player = source.roster.find(player => player.participantId === focus);
    if (!player) throw new Error(`Missing featured participant for sample game ${id}`);
    const { queueId, gameCreationMs, gameVersion, durationMs } = source.summary;
    return {
      matchId: id, queueId, participantId: focus, championName: player.championName,
      championId: player.championId, gameVersion, endItemIds: [...player.endItemIds],
      position: player.teamPosition, win: player.win, remake: false,
      startedAtMs: gameCreationMs, durationSeconds: Math.floor(durationMs / 1000),
      kills: player.kills, deaths: player.deaths, assists: player.assists, cs: player.totalCs,
      gold: player.goldEarned, timelineAvailable: true,
    };
  }).sort((left, right) => right.startedAtMs - left.startedAtMs);
}
