import { readFileSync } from "node:fs";
import { beforeEach, expect, it, vi } from "vitest";
import { getDemoMatch, getMatchDevelopment } from "./development/backend-client";
import { backendFetch } from "./backend-transport";
import { proxyLookup } from "./player-lookup/proxy";
import { GET as health } from "../app/api/health/route";
import { GET as ranks } from "../app/api/matches/[matchId]/ranks/route";

vi.mock("server-only", () => ({}));

const timeline = JSON.parse(readFileSync("../backend/src/main/resources/demo/timeline.json", "utf8"));
const match = JSON.parse(readFileSync("../backend/src/main/resources/demo/match.json", "utf8"));

beforeEach(() => {
  vi.stubEnv("VERCEL", "1");
  vi.stubEnv("VERCEL_ENV", "preview");
  vi.stubEnv("PREVIEW_DATA_SOURCE", "sample");
  vi.stubEnv("BACKEND_URL", "https://backend.example.test");
  vi.stubEnv("BACKEND_SERVICE_TOKEN", "");
  vi.stubGlobal("fetch", vi.fn());
});

it("provides a marked sample without backend configuration or requests", async () => {
  vi.stubEnv("BACKEND_URL", "");
  const demo = await getDemoMatch();
  expect(demo).toEqual({ matchId: "NA1_7000000001", focusParticipantId: 6, compareParticipantId: 1, invented: true });
  expect(demo.matchId).toBe(match.metadata.matchId);
  const data = await getMatchDevelopment(demo.matchId, 6, 1);
  expect(data.roster).toHaveLength(10);
  expect(data.samples).toHaveLength(timeline.info.frames.length);
  expect(JSON.stringify(data)).not.toContain("puuid");
  expect(fetch).not.toHaveBeenCalled();
});

it.each([[6, 1], [1, 6], [7, 2], [2, 7], [6, 2]])(
  "projects the actual synthetic observations for focus %i versus %i",
  async (focus, compare) => {
    const data = await getMatchDevelopment("NA1_7000000001", focus, compare);
    expect(data.summary).toMatchObject({ focusParticipantId: focus, compareParticipantId: compare });
    const player = match.info.participants.find((p: { participantId: number }) => p.participantId === focus);
    expect(data.summary).toMatchObject({ kills: player.kills, win: player.win, goldEarned: player.goldEarned });
    for (const [index, sample] of data.samples.entries()) {
      const frame = timeline.info.frames[index];
      const focal = frame.participantFrames[String(focus)];
      const opponent = frame.participantFrames[String(compare)];
      expect(sample.timestampMs).toBe(frame.timestamp);
      expect(sample.focalTotalGold).toBe(focal?.totalGold ?? null);
      expect(sample.goldDifference).toBe(focal?.totalGold != null && opponent?.totalGold != null ? focal.totalGold - opponent.totalGold : null);
      expect(sample.focalCs).toBe(focal?.minionsKilled != null && focal?.jungleMinionsKilled != null ? focal.minionsKilled + focal.jungleMinionsKilled : null);
      expect(sample.focalXp).toBe(focal?.xp ?? null);
      expect(sample.xpDifference).toBe(focal?.xp != null && opponent?.xp != null ? focal.xp - opponent.xp : null);
      const opponentCs = opponent?.minionsKilled != null && opponent?.jungleMinionsKilled != null
        ? opponent.minionsKilled + opponent.jungleMinionsKilled : null;
      expect(sample.csDifference).toBe(sample.focalCs != null && opponentCs != null ? sample.focalCs - opponentCs : null);
    }
    for (const window of [...data.windows, ...data.suggestedWindows]) {
      expect(window.before).toEqual(data.samples.find((s) => s.timestampMs === window.startMs));
      expect(window.after).toEqual(data.samples.find((s) => s.timestampMs === window.endMs));
      expect(window.summary).toContain(player.championName);
    }
    expect(fetch).not.toHaveBeenCalled();
  },
);

it("does not invent unknown matches or participants or fall back to a backend", async () => {
  await expect(getMatchDevelopment("NA1_123", 6, 1)).rejects.toThrow("MATCH_DEVELOPMENT_NOT_FOUND");
  await expect(getMatchDevelopment("__preview_sample", 6, 1)).rejects.toThrow("MATCH_DEVELOPMENT_NOT_FOUND");
  await expect(getMatchDevelopment("NA1_7000000001", 99, 1)).rejects.toThrow("MATCH_DEVELOPMENT_NOT_FOUND");
  expect(fetch).not.toHaveBeenCalled();
});

it("preserves missing comparison data and rejects an unknown comparator", async () => {
  const data = await getMatchDevelopment("NA1_7000000001", 6);
  expect(data.summary.compareParticipantId).toBeNull();
  expect(data.samples.every((sample) => sample.goldDifference === null && sample.csDifference === null && sample.xpDifference === null)).toBe(true);
  expect(data.windows).toEqual([]);
  expect(data.suggestedWindows).toEqual([]);
  await expect(getMatchDevelopment("NA1_7000000001", 6, 99)).rejects.toThrow("MATCH_DEVELOPMENT_NOT_FOUND");
  expect(fetch).not.toHaveBeenCalled();
});

it("blocks backend transport even when credentials are accidentally present", async () => {
  vi.stubEnv("BACKEND_SERVICE_TOKEN", "0123456789abcdef0123456789abcdef");
  await expect(backendFetch("/api/v1/player-matches", { method: "POST" })).rejects.toThrow("BACKEND_DISABLED_IN_SAMPLE_PREVIEW");
  expect(fetch).not.toHaveBeenCalled();
});

it("reports sample readiness and unavailable live APIs without contacting a backend", async () => {
  const status = await health();
  expect(status.status).toBe(200);
  expect(await status.json()).toEqual({ status: "UP", dataSource: "sample", backend: "NOT_USED" });
  const lookup = await proxyLookup("", { platform: "NA1", gameName: "Invented", tagLine: "DEMO", queueId: 420 });
  expect(lookup.status).toBe(503);
  expect(await lookup.text()).toContain("sample preview");
  expect((await ranks(new Request("https://preview.example.test"), { params: Promise.resolve({ matchId: "NA1_7000000001" }) })).status).toBe(503);
  expect(fetch).not.toHaveBeenCalled();
});

it.each([
  { VERCEL_ENV: "production", LEAGUE_ANALYSIS_RUNTIME: "verification" },
  { VERCEL_ENV: "development", LEAGUE_ANALYSIS_RUNTIME: "verification" },
  { VERCEL: "", VERCEL_ENV: "", LEAGUE_ANALYSIS_RUNTIME: "local" },
  { PREVIEW_DATA_SOURCE: "backend" },
])("preserves backend mode outside explicit sample preview eligibility: %j", async (environment) => {
  for (const [key, value] of Object.entries(environment)) vi.stubEnv(key, value);
  vi.stubEnv("BACKEND_SERVICE_TOKEN", "0123456789abcdef0123456789abcdef");
  vi.mocked(fetch).mockResolvedValue(Response.json({ matchId: "NA1_BACKEND", focusParticipantId: 6, compareParticipantId: 1, invented: true }));
  expect((await getDemoMatch()).matchId).toBe("NA1_BACKEND");
  expect(fetch).toHaveBeenCalledTimes(1);
});

it("supports an explicitly designated local verification runtime", async () => {
  vi.stubEnv("VERCEL", "");
  vi.stubEnv("VERCEL_ENV", "");
  vi.stubEnv("LEAGUE_ANALYSIS_RUNTIME", "verification");
  expect((await getDemoMatch()).matchId).toBe("NA1_7000000001");
  expect(fetch).not.toHaveBeenCalled();
});


it("preserves individual rune counters and distinct participant totals from the seed", async () => {
  const data = await getMatchDevelopment("NA1_7000000001", 6, 1);
  const player = data.roster.find(player => player.participantId === 6)!;
  expect(player.runes?.styles[0].selections[0]).toMatchObject({ runeId: 8437, counters: { var1: 576, var2: 454, var3: 0 } });
  expect(player.runes?.styles[0].selections[1]).toMatchObject({ counters: { var1: null, var2: null, var3: null } });
  expect(player.participantTotals?.totalHeal).toBe(2000);
});

it("keeps synthetic objective totals consistent with the recorded captures and preview events", async () => {
  const data = await getMatchDevelopment("NA1_7000000001", 6, 1);
  const objectives = { dragon: "DRAGON", baron: "BARON_NASHOR", riftHerald: "RIFTHERALD", horde: "HORDE",
    tower: "TOWER_BUILDING", inhibitor: "INHIBITOR_BUILDING" } as const;
  const capturedEvents = timeline.info.frames.flatMap((frame: { events: Record<string, unknown>[] }) => frame.events);
  expect(data.teams).toHaveLength(2);
  for (const team of data.teams!) {
    const capturedTeam = match.info.teams.find((candidate: { teamId: number }) => candidate.teamId === team.teamId);
    for (const [key, kind] of Object.entries(objectives)) {
      const captured = capturedEvents.filter((event: Record<string, unknown>) => {
        const actor = match.info.participants.find((player: { participantId: number }) => player.participantId === event.killerId);
        return actor?.teamId === team.teamId && (event.monsterType === kind || event.buildingType === kind);
      });
      expect(capturedTeam.objectives[key]?.kills, `${team.teamId} ${key} total`).toBe(captured.length);
      expect(team.objectives[key], `${team.teamId} ${key} preview`).toBe(captured.length);
      const projected = data.events.filter(event => event.presentation?.actorTeam.teamId === team.teamId
        && (event.fields?.monsterType === kind || event.fields?.buildingType === kind));
      expect(projected.map(event => event.timestampMs)).toEqual(captured.map((event: { timestamp: number }) => event.timestamp));
      const label = ({ dragon: "Dragon secured", baron: "Baron secured", riftHerald: "Rift Herald secured",
        horde: "Epic monster secured", tower: "Structure destroyed", inhibitor: "Structure destroyed" } as Record<string, string>)[key];
      for (const event of projected) expect(event.label).toBe(label);
    }
  }
  const walkthrough = data.events.filter(event => event.timestampMs >= 480_000 && event.timestampMs <= 600_000);
  expect(walkthrough.map(event => event.timestampMs)).toEqual([505_210, 552_430, 589_775]);
  expect(walkthrough[2]).toMatchObject({ fields: { monsterType: "DRAGON", monsterSubType: "AIR_DRAGON", killerTeamId: 200 } });
  expect(data.samples.find(sample => sample.timestampMs === 480_000)?.goldDifference).toBe(100);
  expect(data.samples.find(sample => sample.timestampMs === 600_000)?.goldDifference).toBe(510);
});
