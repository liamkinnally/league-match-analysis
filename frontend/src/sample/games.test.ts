import { expect, it } from "vitest";
import { sampleGames } from "./games";

it("gives the same featured player eight distinct games, matchups and results in newest-first order", () => {
  expect(sampleGames.map(game => game.id)).toEqual(Array.from({ length: 8 }, (_, i) => `sample-0${i + 1}`));
  const focuses = sampleGames.map(({ source, focus, compare }) => {
    const player = source.roster.find(row => row.participantId === focus)!;
    const opponent = source.roster.find(row => row.participantId === compare)!;
    expect([player.gameName, player.tagLine, player.teamPosition]).toEqual(["Northline", "DEMO", "TOP"]);
    expect(opponent.teamPosition).toBe("TOP");
    expect(opponent.teamId).not.toBe(player.teamId);
    return player;
  });
  expect(new Set(focuses.map(p => p.championId)).size).toBeGreaterThanOrEqual(3);
  expect(new Set(focuses.map(p => p.win)).size).toBe(2);
  expect(new Set(focuses.map(p => p.teamId)).size).toBe(2);
  expect(new Set(sampleGames.map(g => g.source.summary.queueId))).toEqual(new Set([420, 440]));
  expect(new Set(sampleGames.map(g => g.source.teams.find(team => !team.win)!.objectives.tower)).size).toBeGreaterThanOrEqual(3);
  expect(new Set(sampleGames.map(g => g.source.teams.find(team => team.win)!.objectives.dragon)).size).toBeGreaterThanOrEqual(2);
  const dates = sampleGames.map(g => g.source.summary.gameCreationMs);
  expect(dates).toEqual([...dates].sort((a, b) => b - a));
});

it("reconciles each game's scoreboard and final minute with its event history", () => {
  expect(sampleGames).toHaveLength(8);
  for (const { source } of sampleGames) {
    expect(source.roster).toHaveLength(10);
    expect(new Set(source.roster.map(p => p.championId)).size).toBe(10);
    const end = source.frames.at(-1)!;
    expect(end.timestampMs).toBe(source.summary.durationMs);
    expect(new Set(source.frames.map(frame => frame.timestampMs)).size).toBe(source.frames.length);
    expect(source.events.map(e => e.timestampMs)).toEqual(source.events.map(e => e.timestampMs).sort((a, b) => a - b));
    const kills = source.events.filter(e => e.type === "CHAMPION_KILL");
    for (const event of ["WARD_PLACED", "WARD_KILL"]) expect(source.events.some(e => e.type === event)).toBe(true);
    for (const ward of source.events.filter(e => e.type?.startsWith("WARD_"))) {
      const lastDeath = kills.filter(e => e.targetParticipantId === ward.actorParticipantId && e.timestampMs <= ward.timestampMs).at(-1);
      if (lastDeath) {
        const respawn = lastDeath.timestampMs + Math.min(47_000, 10_000 + lastDeath.timestampMs / 65);
        expect(ward.timestampMs).toBeGreaterThanOrEqual(respawn);
      }
    }
    for (const p of source.roster) {
      expect([p.kills, p.deaths, p.assists]).toEqual([
        kills.filter(e => e.actorParticipantId === p.participantId).length,
        kills.filter(e => e.targetParticipantId === p.participantId).length,
        kills.filter(e => e.assisterParticipantIds.includes(p.participantId)).length,
      ]);
      expect(end.participants[p.participantId]).toMatchObject({ cs: p.totalCs, totalGold: p.goldEarned });
      expect(p.laneCs + p.jungleCs).toBe(p.totalCs);
      if (p.teamPosition === "TOP") {
        expect(source.events.some(e => e.type === "ITEM_DESTROYED" && e.itemId === 1222 && e.actorParticipantId === p.participantId)).toBe(true);
        expect(p.runes!.styles[0].selections[0].counters?.var1).toBeGreaterThan(0);
        const final = end.participants[p.participantId];
        if (final.xp! >= 20_340) expect(final.level).toBeGreaterThan(18);
      }
      for (const [i, frame] of source.frames.slice(1).entries()) {
        const last = source.frames[i].participants[p.participantId];
        const next = frame.participants[p.participantId];
        expect(next.cs! >= last.cs! && next.totalGold! >= last.totalGold! && next.xp! >= last.xp!).toBe(true);
        expect(next.level!).toBeGreaterThanOrEqual(last.level!);
      }
    }
    for (const team of source.teams) {
      const members = source.roster.filter(p => p.teamId === team.teamId);
      expect(team.kills).toBe(members.reduce((sum, p) => sum + p.kills, 0));
      expect(team.objectives.dragon).toBe(source.events.filter(e =>
        e.fields?.monsterType === "DRAGON" && e.presentation?.actorTeam.teamId === team.teamId).length);
    }
  }
});

it("gives lanes independent starts and buys affordable items with open inventory slots", () => {
  for (const { source } of sampleGames) {
    const early = source.frames.find(frame => frame.timestampMs === 600_000)!.participants;
    const lanes = [1, 3, 4].map(i => early[i].cs! - early[i + 5].cs!);
    expect(lanes.every(lead => lead > 12) || lanes.every(lead => lead < -12)).toBe(false);
    expect(Math.abs(lanes[0])).toBeLessThan(31);
    for (const p of source.roster) {
      expect(p.goldEarned).toBeGreaterThanOrEqual(p.goldSpent);
      expect(p.endItemIds).toHaveLength(7);
      if (p.endItemIds.slice(0, 6).includes(0)) expect(p.goldEarned - p.goldSpent).toBeLessThan(1750);
    }
  }
});
