import type { MatchDevelopmentParticipant } from "../lib/development/types";
import type { SampleSource } from "../preview/projection";
import { champions, runePage, type Champion } from "./champions";
import { combatEvents, gameEvent, groupEventsByFrame, objectiveEvents, randomSequence, teamOf, visionEvents } from "./game-events";
import { matchInventory } from "./inventory";

export type SampleGame = { id: string; focus: number; compare: number; source: SampleSource };
type Scenario = {
  date: string; seconds: number; top: Champion; opponent: Champion;
  win: boolean; earlyLead: boolean; kills: [number, number]; queue: number; losingTowers: number;
};

// Each row specifies the featured player's result, top-lane start, and both teams' combat pace.
const scenarios: Scenario[] = [
  { date: "2026-09-08T02:14Z", seconds: 1958, top: "Garen", opponent: "Darius", win: true, earlyLead: false, kills: [28, 22], queue: 420, losingTowers: 3 },
  { date: "2026-09-07T22:46Z", seconds: 1733, top: "Ornn", opponent: "Camille", win: false, earlyLead: false, kills: [16, 27], queue: 420, losingTowers: 1 },
  { date: "2026-09-07T20:57Z", seconds: 2111, top: "Camille", opponent: "Jax", win: true, earlyLead: true, kills: [29, 24], queue: 440, losingTowers: 5 },
  { date: "2026-09-06T23:16Z", seconds: 1854, top: "Garen", opponent: "Renekton", win: true, earlyLead: true, kills: [23, 17], queue: 420, losingTowers: 2 },
  { date: "2026-09-06T01:22Z", seconds: 2182, top: "Jax", opponent: "Ornn", win: false, earlyLead: true, kills: [26, 31], queue: 420, losingTowers: 4 },
  { date: "2026-09-05T22:40Z", seconds: 1666, top: "Renekton", opponent: "Darius", win: true, earlyLead: true, kills: [21, 11], queue: 440, losingTowers: 0 },
  { date: "2026-09-05T01:02Z", seconds: 2037, top: "Ornn", opponent: "Jax", win: true, earlyLead: false, kills: [25, 27], queue: 420, losingTowers: 3 },
  { date: "2026-09-04T23:12Z", seconds: 1979, top: "Camille", opponent: "Renekton", win: false, earlyLead: false, kills: [19, 25], queue: 420, losingTowers: 2 },
];

const positions = ["TOP", "JUNGLE", "MIDDLE", "BOTTOM", "UTILITY"];
const alliesByRole: Champion[][] = [
  [], ["Vi", "Sejuani", "LeeSin"], ["Orianna", "Syndra", "Ahri"],
  ["Jinx", "Ashe", "Caitlyn"], ["Nautilus", "Leona", "Thresh"],
];
const aliases = [
  ["Silvergrove", "Ashford", "Mossbank", "Tidepath", "Redvale"],
  ["Rainfern", "Flintbrook", "Waymark", "Saltreed", "Lowcloud"],
  ["Inkwell", "Foxglove", "Skylark", "Vesper", "Reedlight"],
  ["Paleoak", "Westwind", "Moonquill", "Brightmere", "Halftone"],
  ["Bayleaf", "Bracken", "Finchwood", "Dewpoint", "Hearthbell"],
];

function levelAt(xp: number, cap: number) {
  let level = 1, threshold = 280;
  while (level < cap && xp >= threshold) {
    level++;
    threshold += 280 + (level - 1) * 100;
  }
  return level;
}

function buildGame(scenario: Scenario, index: number): SampleGame {
  const { seconds, earlyLead } = scenario;
  const random = randomSequence(7831 + index * 997);
  const focus = index % 2 === 0 ? 1 : 6;
  const compare = focus === 1 ? 6 : 1;
  const focusTeam = teamOf(focus);
  const winner = scenario.win ? focusTeam : 300 - focusTeam;
  const earlyTeam = earlyLead ? focusTeam : 300 - focusTeam;
  const [blueKills, redKills] = focusTeam === 100 ? scenario.kills : [...scenario.kills].reverse();
  const combat = combatEvents(seconds, blueKills, redKills, earlyTeam === 100, random);
  const objectives = objectiveEvents(seconds, winner, earlyTeam, scenario.losingTowers, combat, random);
  const wards = visionEvents(seconds, combat, random);
  const purchases = [];
  const roster: MatchDevelopmentParticipant[] = [];
  const frames: SampleSource["frames"] = [];
  for (let time = 0; time < seconds; time += 60) frames.push({ timestampMs: time * 1000, participants: {} });
  frames.push({ timestampMs: seconds * 1000, participants: {} });

  for (let id = 1; id <= 10; id++) {
    const role = (id - 1) % 5, side = id <= 5 ? 0 : 1;
    const teamId = teamOf(id);
    const name = role === 0 ? (id === focus ? scenario.top : scenario.opponent)
      : alliesByRole[role][(index * (role === 2 ? 2 : 1) + role + side) % 3];
    const champion = champions[name];
    const kills = combat.filter(e => e.actorParticipantId === id);
    const deaths = combat.filter(e => e.targetParticipantId === id);
    const assists = combat.filter(e => e.assisterParticipantIds.includes(id));
    const pace = Math.max(.55, [7.25, 5.9, 7.45, 7.8, 1.1][role] + (random() - .5) * 1.5
      + (teamId === winner ? .25 : -.25));
    const totalCs = Math.round(seconds / 60 * pace);
    const blueStart = role === 0 ? (earlyTeam === 100 ? .028 : -.028) : ((index + role) % 3 - 1) * .024;
    const laneStart = (side === 0 ? blueStart : -blueStart) + (random() - .5) * .012;
    const xpPerMinute = [530, 453, 516, 450, 380][role] + (random() - .5) * 47;
    const plateGold = role === 1 || role === 4 ? 0 : Math.floor(random() * (teamId === earlyTeam ? 4 : 3)) * 125;
    const topQuestAt = role === 0 ? 810 + Math.round(random() * 115) : Infinity;
    if (role === 0) purchases.push(gameEvent(topQuestAt, "ITEM_DESTROYED", "Top lane quest completed",
      id, { participantId: id, itemId: 1222 }));
    let gold = 500;
    for (const frame of frames) {
      const time = frame.timestampMs / 1000;
      const progress = Math.max(0, (time - 92) / (seconds - 92));
      // Opponents with different lane starts converge on their final farm totals at game end.
      const cs = Math.round(totalCs * (progress + laneStart * Math.sin(progress * Math.PI)));
      const observedKills = kills.filter(e => e.timestampMs <= frame.timestampMs).length;
      const observedAssists = assists.filter(e => e.timestampMs <= frame.timestampMs).length;
      const structures = objectives.filter(e => e.type === "BUILDING_KILL" && e.presentation?.actorTeam.teamId === teamId
        && e.timestampMs <= frame.timestampMs).length;
      const passive = Math.max(0, time - 110) * (role === 4 ? 3.4 : 2.04);
      const plates = plateGold * Math.max(0, Math.min(1, (time - 360) / 480));
      gold = Math.round(500 + passive + cs * (role === 1 ? 27 : 23.5)
        + observedKills * 315 + observedAssists * 88 + structures * 95 + plates);
      const questComplete = time >= topQuestAt;
      const questBonus = questComplete ? 600 + (time - topQuestAt) / 60 * xpPerMinute * .11 : 0;
      const xp = Math.round(progress * (seconds / 60 * xpPerMinute)
        + observedKills * 105 + observedAssists * 50 + questBonus);
      frame.participants[id] = { totalGold: gold, cs, xp, level: levelAt(xp, questComplete ? 20 : 18) };
    }
    const inventory = matchInventory(id, champion.items, frames);
    purchases.push(...inventory.events);
    const jungleCs = Math.round(totalCs * (role === 1 ? .93 : .025));
    const takedowns = kills.length + assists.length;
    const damageToChampions = Math.round(seconds / 60 * [400, 335, 515, 490, 170][role]
      + kills.length * 710 + assists.length * 205 + random() * 800);
    const totalHeal = Math.round((role === 1 ? 340 : 104) * seconds / 60 + takedowns * 85);
    const alliedShield = inventory.endItemIds.includes(3190) ? 420 + takedowns * 147 : 0;
    const ownWards = wards.filter(e => e.actorParticipantId === id);
    const participant: MatchDevelopmentParticipant = {
      participantId: id, teamId, gameName: id === focus ? "Northline" : aliases[role][(index + role + side * 2) % 5],
      tagLine: "DEMO", championId: champion.id, championName: name, teamPosition: positions[role],
      win: teamId === winner, kills: kills.length, deaths: deaths.length, assists: assists.length,
      laneCs: totalCs - jungleCs, jungleCs, totalCs, goldEarned: gold, goldSpent: inventory.goldSpent,
      visionScore: Math.round(ownWards.reduce((score, ward) => score + (ward.type === "WARD_PLACED" ? 1.6 : 2.2), 0)),
      summonerSpellOneId: 4, summonerSpellTwoId: [12, 11, 12, 21, 3][role],
      endItemIds: inventory.endItemIds,
      runes: runePage(champion.runes, seconds / 60, takedowns),
      participantTotals: { totalDamageDealt: damageToChampions + totalCs * (role === 1 ? 790 : 405),
        totalDamageDealtToChampions: damageToChampions, totalHeal, totalHealsOnTeammates: 0,
        totalDamageShieldedOnTeammates: alliedShield },
    };
    roster.push(participant);
  }

  const teams = [100, 200].map(teamId => {
    const members = roster.filter(p => p.teamId === teamId);
    const own = objectives.filter(e => e.presentation?.actorTeam.teamId === teamId);
    const sum = (stat: "kills" | "deaths" | "assists" | "goldEarned") => members.reduce((total, p) => total + p[stat], 0);
    const count = (key: string, value: string) => own.filter(e => e.fields?.[key] === value).length;
    return { teamId, win: teamId === winner, kills: sum("kills"), deaths: sum("deaths"), assists: sum("assists"),
      goldEarned: sum("goldEarned"), objectives: { tower: count("buildingType", "TOWER_BUILDING"),
        inhibitor: count("buildingType", "INHIBITOR_BUILDING"), dragon: count("monsterType", "DRAGON"),
        horde: count("monsterType", "HORDE"), riftHerald: count("monsterType", "RIFTHERALD"), baron: count("monsterType", "BARON_NASHOR") } };
  });
  return {
    id: `sample-0${index + 1}`, focus, compare,
    source: {
      summary: { queueId: scenario.queue, mapId: 11, gameMode: "CLASSIC", gameVersion: "16.17.1",
        gameCreationMs: Date.parse(scenario.date), durationMs: seconds * 1000 },
      roster, teams, frames, events: groupEventsByFrame([...combat, ...objectives, ...wards, ...purchases], seconds * 1000),
    },
  };
}

export const sampleGames = scenarios.map(buildGame);
