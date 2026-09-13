import type {
  FinalTeamResult,
  MatchDevelopment,
  MatchDevelopmentParticipant,
  PlayerRank,
} from "./types";
import type { GameAssetCatalog } from "../game-assets/types";

export const participantName = (
  person: MatchDevelopmentParticipant,
  assets: GameAssetCatalog | null,
) => assets?.champions[String(person.championId)]?.name ?? person.championName;
export const playerName = (person: MatchDevelopmentParticipant) =>
  person.gameName || person.summonerName || "Name unavailable";
export const riotId = (person: MatchDevelopmentParticipant) =>
  `${playerName(person)}${person.tagLine ? `#${person.tagLine}` : ""}`;
export const roleLabel = (role: string) =>
  role === "UTILITY" ? "SUPPORT" : role;
export const queueLabel = (queueId: number) =>
  ({
    0: "All queues",
    420: "Ranked Solo/Duo",
    440: "Ranked Flex",
    400: "Draft Pick",
    430: "Blind Pick (historical)",
    450: "ARAM",
    480: "Swiftplay",
    490: "Quickplay (historical)",
  })[queueId] ?? `Queue ${queueId}`;
export const queueType = (queueId: number) =>
  queueId === 420
    ? "RANKED_SOLO_5x5"
    : queueId === 440
      ? "RANKED_FLEX_SR"
      : null;
export const queueShort = (queueId: number) =>
  queueId === 420 ? "Solo/Duo" : queueId === 440 ? "Flex" : queueLabel(queueId);
export const numberText = (value: number | null | undefined) =>
  typeof value === "number" && Number.isFinite(value)
    ? value.toLocaleString("en-US")
    : "Unavailable";

export function finalItemSlotOrder(itemIds: readonly number[]): number[] {
  const regular = Array.from({ length: 6 }, (_, i) => i);
  return [
    ...regular.filter((i) => (itemIds[i] ?? 0) > 0),
    ...regular.filter((i) => !(itemIds[i] > 0)),
    6,
  ];
}
export function objectiveColumns(version: string, mapId: number) {
  if (![11, 12, 14].includes(mapId)) return [];
  const [major, minor] = version.split(".").map(Number);
  const columns = [
    { key: "tower", label: "Towers" },
    { key: "inhibitor", label: "Inhibitors" },
  ];
  if (mapId !== 11) return columns;
  columns.push(
    { key: "dragon", label: "Dragons" },
    { key: "baron", label: "Barons" },
  );
  if (major > 5 || (major === 5 && minor >= 22))
    columns.push({ key: "riftHerald", label: "Heralds" });
  if (major >= 14) columns.push({ key: "horde", label: "Void grubs" });
  if (major === 15) columns.push({ key: "atakhan", label: "Atakhan" });
  return columns;
}
export function finalTeams(data: MatchDevelopment): FinalTeamResult[] {
  if (data.teams?.length) return data.teams;
  return [...new Set(data.roster.map((p) => p.teamId))].map((teamId) => {
    const players = data.roster.filter((p) => p.teamId === teamId);
    const sum = (key: "kills" | "deaths" | "assists" | "goldEarned") =>
      players.length === 5 && players.every((p) => Number.isFinite(p[key]))
        ? players.reduce((total, p) => total + p[key], 0)
        : null;
    return {
      teamId,
      win:
        players.every((p) => p.win === players[0].win) &&
        typeof players[0].win === "boolean"
          ? players[0].win
          : null,
      kills: sum("kills"),
      deaths: sum("deaths"),
      assists: sum("assists"),
      goldEarned: sum("goldEarned"),
      objectives: {},
    };
  });
}
const TIERS = [
  "IRON",
  "BRONZE",
  "SILVER",
  "GOLD",
  "PLATINUM",
  "EMERALD",
  "DIAMOND",
  "MASTER",
  "GRANDMASTER",
  "CHALLENGER",
];
const DIVISIONS = ["IV", "III", "II", "I"];
export const AVERAGE_RANK_METHOD =
  "Mean of confirmed current ranks in the selected queue, rounded to the nearest step (halfway rounds higher). Iron IV = 0 through Diamond I = 27; Master = 28, Grandmaster = 29, Challenger = 30. Each high tier is one equal display step. Unranked and unavailable players are excluded. LP is ignored; this is not MMR or a historical match rank.";
export const rankEmblem = (tier: string) =>
  `https://raw.communitydragon.org/latest/plugins/rcp-fe-lol-shared-components/global/default/images/${tier.toLowerCase()}.png`;
export function rankOrdinal(
  tier: string | null | undefined,
  division: string | null | undefined,
): number | null {
  const t = TIERS.indexOf(tier ?? "");
  if (t < 0) return null;
  if (t >= 7) return division == null || division === "I" ? t + 21 : null;
  const d = DIVISIONS.indexOf(division ?? "");
  return d < 0 ? null : t * 4 + d;
}
function ordinalDisplay(value: number) {
  const tier = value < 28 ? TIERS[Math.floor(value / 4)] : TIERS[value - 21],
    division = value < 28 ? DIVISIONS[value % 4] : null;
  const label =
    tier[0] + tier.slice(1).toLowerCase() + (division ? ` ${division}` : "");
  return { label, tier, division, emblemUrl: rankEmblem(tier) };
}
export function rankDisplay(record: PlayerRank | undefined) {
  const ordinal =
    record?.status === "ranked"
      ? rankOrdinal(record.tier, record.division)
      : null;
  if (ordinal !== null)
    return { ...ordinalDisplay(ordinal), status: "ranked", ordinal };
  const status =
    record?.status === "unranked"
      ? "unranked"
      : record?.status === "loading"
        ? "loading"
        : "unavailable";
  return {
    status,
    ordinal: null,
    label:
      status === "unranked"
        ? "Unranked"
        : status === "loading"
          ? "Loading…"
          : "Unavailable",
    tier: null,
    division: null,
    emblemUrl: status === "unranked" ? rankEmblem("unranked") : null,
  };
}
export function currentRankAverage(
  records: PlayerRank[],
  totalPlayers: number,
) {
  const values = records
    .map(rankDisplay)
    .map((r) => r.ordinal)
    .filter((v): v is number => v !== null);
  const mean = values.length
    ? values.reduce((sum, v) => sum + v, 0) / values.length
    : null;
  return {
    ...(mean === null
      ? { label: null, tier: null, division: null, emblemUrl: null }
      : ordinalDisplay(Math.round(mean))),
    contributors: values.length,
    totalPlayers,
    mean,
    method: AVERAGE_RANK_METHOD,
  };
}
