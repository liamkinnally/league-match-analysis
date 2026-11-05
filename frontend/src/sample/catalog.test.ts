import { expect, it, vi } from "vitest";
import { getSampleDevelopment, getSampleHistory } from "./catalog";

vi.mock("server-only", () => ({}));

it("opens every profile history row on the matching player, outcome and timeline", () => {
  const history = getSampleHistory();
  expect(history.length).toBeGreaterThan(1);
  expect(new Set(history.map(row => row.matchId)).size).toBe(history.length);
  expect(history.map(row => row.startedAtMs)).toEqual(history.map(row => row.startedAtMs).toSorted((a, b) => b - a));

  for (const row of history) {
    const match = getSampleDevelopment(row.matchId, row.participantId)!;
    const focal = match.roster.find(player => player.participantId === row.participantId)!;
    expect(row).toEqual({
      matchId: match.matchId, participantId: match.summary.focusParticipantId, queueId: match.summary.queueId,
      startedAtMs: match.summary.gameCreationMs, durationSeconds: Math.floor(match.summary.durationMs / 1000),
      gameVersion: match.summary.gameVersion, championName: focal.championName, championId: focal.championId,
      endItemIds: focal.endItemIds, position: focal.teamPosition, win: focal.win, remake: false,
      kills: focal.kills, deaths: focal.deaths, assists: focal.assists, cs: focal.totalCs, gold: focal.goldEarned,
      timelineAvailable: match.timelineAvailable,
    });
    expect(match.timelineAvailable).toBe(true);
    expect(match.samples.length).toBeGreaterThan(1);
  }
});

it("only projects known sample matches and roster participants", () => {
  const [row] = getSampleHistory();
  expect(getSampleDevelopment("not-a-sample", row.participantId)).toBeUndefined();
  expect(getSampleDevelopment(row.matchId, 99)).toBeUndefined();
  expect(getSampleDevelopment(row.matchId, row.participantId, 99)).toBeUndefined();
});
