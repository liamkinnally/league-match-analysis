import { queueType } from "../lib/development/results";
import type { CurrentRanks, MatchDevelopment, PlayerRank } from "../lib/development/types";
import { samplePlayerProfile } from "./profile";

// Recurring fictional players keep the same queue-specific rank across games.
const opponents = [
  { names: ["Silvergrove", "Rainfern", "Inkwell", "Paleoak", "Bayleaf"], solo: ["GRANDMASTER", "I", 834], flex: ["DIAMOND", "II", 46] },
  { names: ["Ashford", "Flintbrook", "Foxglove", "Westwind", "Bracken"], solo: ["CHALLENGER", "I", 1142], flex: ["MASTER", "I", 135] },
  { names: ["Mossbank", "Waymark", "Skylark", "Moonquill", "Finchwood"], solo: ["MASTER", "I", 655], flex: ["DIAMOND", "I", 78] },
  { names: ["Tidepath", "Saltreed", "Vesper", "Brightmere", "Dewpoint"], solo: ["CHALLENGER", "I", 1308], flex: ["DIAMOND", "II", 25] },
  { names: ["Redvale", "Lowcloud", "Reedlight", "Halftone", "Hearthbell"], solo: ["GRANDMASTER", "I", 941], flex: ["MASTER", "I", 62] },
] as const;

export function sampleMatchRanks(data: MatchDevelopment): CurrentRanks {
  const solo = data.summary.queueId === 420;
  const players = data.roster.map((player): PlayerRank => {
    const { participantId, gameName, tagLine } = player;
    if (gameName === samplePlayerProfile.identity.gameName && tagLine === samplePlayerProfile.identity.tagLine) {
      const { status, tier, division, leaguePoints } = solo ? samplePlayerProfile.soloRank : samplePlayerProfile.flexRank;
      return { participantId, status, tier, division, leaguePoints };
    }
    const opponent = opponents.find(({ names }) => names.some(name => name === gameName));
    if (!opponent) return { participantId, status: "unavailable" };
    const [tier, division, leaguePoints] = solo ? opponent.solo : opponent.flex;
    return { participantId, status: "ranked", tier, division, leaguePoints };
  });
  return { matchId: data.matchId, queueType: queueType(data.summary.queueId), refreshing: false, players };
}
