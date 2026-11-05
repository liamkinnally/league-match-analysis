import type { MatchDevelopmentEvent } from "../lib/development/types";

export function teamOf(participant: number) { return participant <= 5 ? 100 : 200; }

function participants(team: number) { return team === 100 ? [1, 2, 3, 4, 5] : [6, 7, 8, 9, 10]; }

function respawnAt(deathSeconds: number) {
  return deathSeconds + Math.min(47, 10 + deathSeconds / 65);
}

function aliveAt(kills: MatchDevelopmentEvent[], id: number, time: number) {
  return !kills.some(e => e.targetParticipantId === id && time >= e.timestampMs / 1000
    && time < respawnAt(e.timestampMs / 1000));
}

// A fixed seed describes one invented match. Refreshes and different participant views replay it exactly.
export function randomSequence(seed: number) {
  let state = seed;
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 4294967296;
  };
}
function weighted(ids: number[], weights: number[], random: () => number) {
  let remaining = random() * ids.reduce((sum, id) => sum + weights[(id - 1) % 5], 0);
  for (const id of ids) {
    remaining -= weights[(id - 1) % 5];
    if (remaining <= 0) return id;
  }
  return ids[ids.length - 1];
}

export function gameEvent(seconds: number, type: string, label: string, actor: number,
  fields: Record<string, unknown>, target: number | null = null, assisters: number[] = []): MatchDevelopmentEvent {
  const timestampMs = Math.round(seconds * 1000);
  return { timestampMs, type, label, participantIds: [actor, ...(target === null ? [] : [target]), ...assisters].sort((a, b) => a - b),
    itemId: typeof fields.itemId === "number" ? fields.itemId : null,
    actorParticipantId: actor, targetParticipantId: target, assisterParticipantIds: assisters, assistersObserved: true,
    fields: { type, timestamp: timestampMs, ...fields },
    presentation: { actorTeam: { teamId: teamOf(actor), basis: "known" }, objectTeam: type === "BUILDING_KILL"
      ? { teamId: Number(fields.teamId), basis: "known" } : { teamId: null, basis: "missing" } },
    x: null, y: null,
  };
}

export function combatEvents(duration: number, blueKills: number, redKills: number,
  earlyBlue: boolean, random: () => number) {
  const times = [blueKills, redKills].flatMap((total, side) => Array.from({ length: total }, (_, i) => {
    const fraction = (i + .25 + random() * .5) / total;
    // A side that controls the early game earns more of its kills before the midpoint.
    const exponent = (side === 0) === earlyBlue ? 1.08 : .82;
    return { side: side === 0 ? 100 : 200, time: 145 + (duration - 160) * fraction ** exponent };
  })).sort((a, b) => a.time - b.time);

  const deadUntil = new Map<number, number>();
  const events: MatchDevelopmentEvent[] = [];
  for (const { side, time } of times) {
    const alive = (team: number) => participants(team).filter(id => (deadUntil.get(id) ?? 0) <= time);
    const allies = alive(side), enemies = alive(300 - side);
    if (!allies.length || !enemies.length) continue;
    const actor = weighted(allies, [23, 22, 24, 28, 3], random);
    const paired = enemies.find(id => (id - 1) % 5 === (actor - 1) % 5);
    const victim = time < 780 && paired && random() < .7 ? paired : weighted(enemies, [22, 17, 20, 22, 19], random);
    const available = allies.filter(id => id !== actor);
    const count = Math.min(available.length, Math.floor(random() * (time < 780 ? 3 : 4)));
    const assisters = [];
    for (let i = 0; i < count; i++) {
      const assister = weighted(available, [13, 27, 18, 13, 29], random);
      assisters.push(assister);
      available.splice(available.indexOf(assister), 1);
    }
    events.push(gameEvent(time, "CHAMPION_KILL", "Champion kill", actor,
      { killerId: actor, victimId: victim, assistingParticipantIds: assisters }, victim, assisters));
    deadUntil.set(victim, respawnAt(time));
  }
  return events;
}

export function objectiveEvents(duration: number, winningTeam: number, earlyTeam: number, losingTowers: number,
  kills: MatchDevelopmentEvent[], random: () => number) {
  const events: MatchDevelopmentEvent[] = [];
  const byTeam = (team: number, time: number, preferredRole: number) => {
    const alive = participants(team).filter(id => aliveAt(kills, id, time));
    return alive.find(id => (id - 1) % 5 === preferredRole) ?? alive[0];
  };
  function monster(time: number, team: number, monsterType: string, subtype?: string) {
    const actor = byTeam(team, time, 1);
    if (!actor) return;
    events.push(gameEvent(time, "ELITE_MONSTER_KILL", "Epic monster secured", actor,
      { killerId: actor, killerTeamId: team, monsterType, ...(subtype && { monsterSubType: subtype }) }));
  }
  const dragons = ["AIR_DRAGON", "FIRE_DRAGON", "EARTH_DRAGON", "WATER_DRAGON"];
  const rotation = Math.floor(random() * dragons.length);
  let drake = 344 + random() * 55;
  for (let i = 0; drake < duration - 75 && i < 4; i++, drake += 326 + random() * 93) {
    const team = i === 0 ? earlyTeam : random() < .64 ? winningTeam : 300 - winningTeam;
    monster(drake, team, "DRAGON", dragons[(rotation + Math.min(i, 2)) % dragons.length]);
  }
  const grubs = 535 + random() * 55;
  for (let i = 0; i < 3; i++) monster(grubs + 11 * i, earlyTeam, "HORDE");
  monster(975 + random() * 55, earlyTeam, "RIFTHERALD");
  if (duration > 1750) monster(Math.min(duration - 76, 1530 + random() * 140), winningTeam, "BARON_NASHOR");

  function building(time: number, team: number, lane: string, tower?: string) {
    const actor = byTeam(team, time, lane === "TOP_LANE" ? 0 : lane === "MID_LANE" ? 2 : 3);
    if (!actor) return;
    events.push(gameEvent(time, "BUILDING_KILL", "Structure destroyed", actor, {
      killerId: actor, teamId: 300 - team, laneType: lane,
      buildingType: tower ? "TOWER_BUILDING" : "INHIBITOR_BUILDING", ...(tower && { towerType: tower }),
    }));
  }
  const losingLanes = ["TOP_LANE", "BOT_LANE", "MID_LANE", "TOP_LANE", "BOT_LANE"];
  for (let i = 0; i < losingTowers; i++) {
    building(780 + i * ((duration - 1000) / losingTowers) + random() * 60, 300 - winningTeam,
      losingLanes[i], i < 3 ? "OUTER_TURRET" : "INNER_TURRET");
  }
  building(710 + random() * 150, winningTeam, "TOP_LANE", "OUTER_TURRET");
  if (duration > 1830) building(duration * .63, winningTeam, "BOT_LANE", "OUTER_TURRET");
  building(duration * .66, winningTeam, "MID_LANE", "OUTER_TURRET");
  building(duration * .78, winningTeam, "MID_LANE", "INNER_TURRET");
  building(duration - 108, winningTeam, "MID_LANE", "BASE_TURRET");
  building(duration - 95, winningTeam, "MID_LANE");
  building(duration - 47, winningTeam, "MID_LANE", "NEXUS_TURRET");
  building(duration - 28, winningTeam, "MID_LANE", "NEXUS_TURRET");
  return events;
}

export function groupEventsByFrame(events: MatchDevelopmentEvent[], durationMs: number) {
  const counts = new Map<number, number>();
  return events.sort((a, b) => a.timestampMs - b.timestampMs).map(e => {
    const frameAtMs = Math.min(durationMs, Math.ceil(e.timestampMs / 60_000) * 60_000);
    const frameEventIndex = counts.get(frameAtMs) ?? 0;
    counts.set(frameAtMs, frameEventIndex + 1);
    return { ...e, frameAtMs, frameEventIndex };
  });
}

export function visionEvents(duration: number, combat: MatchDevelopmentEvent[], random: () => number) {
  const events: MatchDevelopmentEvent[] = [];
  for (let id = 1; id <= 10; id++) {
    const role = (id - 1) % 5;
    for (let time = 112 + random() * 86; time < duration - 50;) {
      const supportWards = role === 4 && time > 520;
      const wardType = supportWards ? "SIGHT_WARD" : "YELLOW_TRINKET";
      if (aliveAt(combat, id, time)) {
        events.push(gameEvent(time, "WARD_PLACED", "Ward placed", id, { creatorId: id, wardType }));
        const clearedAt = time + 20 + random() * 55;
        const enemy = participants(300 - teamOf(id))[random() < .5 ? 1 : 4];
        if (clearedAt < duration && random() < .36 && aliveAt(combat, enemy, clearedAt)) {
          const clear = gameEvent(clearedAt, "WARD_KILL", "Ward destroyed", enemy, { killerId: enemy, wardType });
          clear.presentation!.objectTeam = { teamId: teamOf(id), basis: "known" };
          events.push(clear);
        }
      }
      if (role === 1) break; // The jungler swaps to a sweeper after the first ward.
      time += supportWards ? 72 + random() * 40 : 188 + random() * 42;
    }
  }
  return events;
}
