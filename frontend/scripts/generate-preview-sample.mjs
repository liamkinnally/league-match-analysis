// Regenerate the public UI sample from the same invented inputs as --seed-demo.
import { readFileSync, writeFileSync } from "node:fs";

const source = new URL("../../backend/src/main/resources/demo/", import.meta.url);
const match = JSON.parse(readFileSync(new URL("match.json", source), "utf8")).info;
const timeline = JSON.parse(readFileSync(new URL("timeline.json", source), "utf8")).info;
// Preserve the sample's recorded counters; the matching server catalog supplies display labels.
const patch = match.gameVersion.split(".").slice(0, 2).join(".");
function runes(player) {
  return { availability: player.perks ? "available" : "missing", matchPatch: patch,
    normalizationVersion: "participant-details-v1", layoutManifestId: `rune-layout-${patch}-v1`,
    performanceStatus: player.perks ? "unverified" : "missing",
    styles: (player.perks?.styles ?? []).map(style => ({ styleId: style.style, role: style.description,
      selections: style.selections.map(selection => ({ runeId: selection.perk, metrics: [],
        counters: Object.fromEntries(["var1", "var2", "var3"].map(key => [key, selection[key] ?? null])),
      })),
    })),
    shards: { offense: player.perks?.statPerks.offense ?? null, flex: player.perks?.statPerks.flex ?? null, defense: player.perks?.statPerks.defense ?? null } };
}
const roster = match.participants.map((player) => ({
  participantId: player.participantId, teamId: player.teamId,
  championId: player.championId, championName: player.championName,
  teamPosition: player.teamPosition, win: player.win,
  kills: player.kills, deaths: player.deaths, assists: player.assists,
  laneCs: player.totalMinionsKilled, jungleCs: player.neutralMinionsKilled,
  totalCs: player.totalMinionsKilled + player.neutralMinionsKilled,
  goldEarned: player.goldEarned, goldSpent: player.goldSpent, visionScore: player.visionScore,
  summonerSpellOneId: player.summoner1Id, summonerSpellTwoId: player.summoner2Id,
  endItemIds: Array.from({ length: 7 }, (_, index) => player[`item${index}`]),
  gameName: player.riotIdGameName ?? null, tagLine: player.riotIdTagline ?? null,
  summonerName: player.summonerName ?? null,
  runes: runes(player),
  participantTotals: Object.fromEntries(["totalDamageDealt", "totalDamageDealtToChampions", "totalHeal", "totalHealsOnTeammates", "totalDamageShieldedOnTeammates"].map(key => [key, player[key] ?? null])),
}));
const eventFields = ["type", "timestamp", "participantId", "killerId", "victimId", "killerTeamId",
  "assistingParticipantIds", "position", "itemId", "monsterType", "monsterSubType",
  "teamId", "buildingType", "towerType", "laneType"];
const sample = {
  source: "backend/src/main/resources/demo/{match,timeline}.json — demo-match-v1; regenerate with node frontend/scripts/generate-preview-sample.mjs",
  summary: { queueId: match.queueId, mapId: match.mapId, gameMode: match.gameMode,
    gameVersion: match.gameVersion, gameCreationMs: match.gameCreation, durationMs: match.gameDuration * 1000 },
  roster,
  teams: match.teams.map((team) => {
    const members = roster.filter((player) => player.teamId === team.teamId);
    return { teamId: team.teamId, win: team.win,
      ...Object.fromEntries(["kills", "deaths", "assists", "goldEarned"].map((field) => [field,
        members.length === 5 ? members.reduce((sum, player) => sum + player[field], 0) : null])),
      objectives: Object.fromEntries(["tower", "inhibitor", "dragon", "baron", "riftHerald", "horde"]
        .map((key) => [key, team.objectives[key]?.kills ?? null])),
    };
  }),
  frames: timeline.frames.map((frame) => ({ timestampMs: frame.timestamp,
    participants: Object.fromEntries(Object.entries(frame.participantFrames).map(([id, observation]) => [id, {
      totalGold: observation.totalGold ?? null, xp: observation.xp ?? null, level: observation.level ?? null,
      cs: observation.minionsKilled != null && observation.jungleMinionsKilled != null
        ? observation.minionsKilled + observation.jungleMinionsKilled : null,
    }])),
  })),
  events: timeline.frames.flatMap((frame) => frame.events.map((event, index) => {
    const actor = event.participantId ?? event.killerId ?? null;
    const target = event.victimId ?? null;
    const assists = event.assistingParticipantIds ?? [];
    const label = { ITEM_PURCHASED: `Purchased item ${event.itemId}`, CHAMPION_KILL: "Champion kill",
      ELITE_MONSTER_KILL: { DRAGON: "Dragon secured", BARON_NASHOR: "Baron secured",
        RIFTHERALD: "Rift Herald secured" }[event.monsterType] ?? "Epic monster secured",
      BUILDING_KILL: "Structure destroyed" }[event.type];
    if (!label) throw new Error(`Unsupported synthetic event: ${event.type}`);
    const actorTeamId = roster.find(player => player.participantId === actor)?.teamId ?? null;
    return { timestampMs: event.timestamp, label, type: event.type,
      presentation: { actorTeam: { teamId: actorTeamId, basis: actorTeamId === null ? "missing" : "known" },
        objectTeam: { teamId: null, basis: event.type === "BUILDING_KILL" ? "unsupported" : "missing" } },
      participantIds: [...new Set([actor, target, ...assists].filter((id) => id > 0))].sort((a, b) => a - b),
      itemId: event.itemId ?? null, actorParticipantId: actor, targetParticipantId: target,
      assisterParticipantIds: assists, assistersObserved: Array.isArray(event.assistingParticipantIds),
      frameAtMs: frame.timestamp, frameEventIndex: index,
      fields: Object.fromEntries(eventFields.filter((key) => key in event).map((key) => [key, event[key]])),
      x: event.position?.x ?? null, y: event.position?.y ?? null,
    };
  })).sort((left, right) => left.timestampMs - right.timestampMs || left.frameAtMs - right.frameAtMs || left.frameEventIndex - right.frameEventIndex),
};
writeFileSync(new URL("../src/preview/sample.json", import.meta.url), `${JSON.stringify(sample, null, 2)}\n`);
