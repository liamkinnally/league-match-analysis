import { beforeEach, expect, it, vi } from "vitest";
import { POST } from "../../app/api/player-matches/route";
import { parseLookup } from "./types";
import { GET } from "../../app/api/player-matches/[runId]/route";

vi.mock("server-only", () => ({}));

const runId = "00000000-0000-0000-0000-000000000001";
const lookup = { platform: "NA1", runId, gameName: "Invented", tagLine: "NA1", status: "RUNNING", message: null, retryNotBefore: null, queueId: 420, lastUpdated: null, nextRefreshAt: null, previousRunId: null, hasMore: true, matches: [] };
beforeEach(() => vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080"));

it("forwards only validated Riot ID fields and strips private upstream data", async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ ...lookup, resolvedPuuid: "private-puuid", raw: "private-body" }, { status: 202 }));
  vi.stubGlobal("fetch", fetcher);
  const response = await POST(new Request("http://localhost/api/player-matches", { method: "POST", headers: { "X-Forwarded-For": "spoof", "Content-Type": "application/json" }, body: JSON.stringify({ gameName: " Invented ", tagLine: "NA1" }) }));
  expect(response.status).toBe(202);
  expect(await response.json()).toEqual(lookup);
  const request = fetcher.mock.calls[0][0] as Request;
  expect(request.headers.get("content-type")).toBe("application/json");
  expect(request.headers.has("authorization")).toBe(false);
  expect(await request.text()).toBe('{"gameName":"Invented","tagLine":"NA1","queueId":0,"platform":"NA1"}');
});
it("validates before contacting backend and never proxies arbitrary paths", async () => {
  const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
  expect((await POST(new Request("http://localhost", { method: "POST", body: '{}' }))).status).toBe(400);
  expect((await GET(new Request("http://localhost"), { params: Promise.resolve({ runId: "../private" }) })).status).toBe(400);
  expect(fetcher).not.toHaveBeenCalled();
});
it("retains provider cooldown while discarding error bodies", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ message: "secret", retryNotBefore: "2026-09-09T13:00:00Z" }, { status: 429, headers: { "Retry-After": "120" } })));
  const response = await GET(new Request("http://localhost"), { params: Promise.resolve({ runId }) });
  expect(response.status).toBe(429);
  expect(response.headers.get("Retry-After")).toBe("120");
  expect(await response.text()).not.toContain("secret");
});
it("fails safely on malformed upstream data", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ raw: "private-body" })));
  const response = await GET(new Request("http://localhost"), { params: Promise.resolve({ runId }) });
  expect(response.status).toBe(502);
  expect(await response.text()).not.toContain("private-body");
});

it("returns service unavailable without a configured backend", async () => {
  vi.stubEnv("BACKEND_URL", "");
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  const response = await POST(new Request("http://localhost/api/player-matches", {
    method: "POST",
    body: JSON.stringify({ gameName: "Invented", tagLine: "NA1" }),
  }));
  expect(response.status).toBe(503);
  expect(fetcher).not.toHaveBeenCalled();
});

it("projects version and ordered final item IDs for patch-matched history assets", () => {
  const result = parseLookup({ ...lookup, matches: [{ matchId: "NA1_7000000002", queueId: 420, participantId: 6,
    championName: "Garen", championId: 86, gameVersion: "16.17.1", endItemIds: [3071, 3047, 3053, 6333, 3065, 0, 3364],
    position: "TOP", win: true, startedAtMs: 1788890400000, durationSeconds: 1800, kills: 8, deaths: 3, assists: 7,
    cs: 214, gold: 13800, timelineAvailable: true, raw: "private" }] });
  expect(result.matches[0].gameVersion).toBe("16.17.1");
  expect(result.matches[0].endItemIds).toEqual([3071, 3047, 3053, 6333, 3065, 0, 3364]);
  expect(result.matches[0]).not.toHaveProperty("raw");
});

it("serves completed mixed-queue history containing six-digit ARAM item IDs", async () => {
  const completed = { ...lookup, queueId: 0, status: "COMPLETE", matches: [{
    matchId: "NA1_7000000003", queueId: 450, participantId: 1, championName: "MissFortune", championId: 21,
    gameVersion: "16.18.817.5716", endItemIds: [126697, 1001, 1036, 1036, 1036, 1036, 2052],
    position: "UNKNOWN", win: false, remake: false, startedAtMs: 1789504256758, durationSeconds: 608,
    kills: 5, deaths: 6, assists: 10, cs: 22, gold: 7267, timelineAvailable: false,
  }] };
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => Response.json(completed)));
  const response = await GET(new Request("http://localhost"), { params: Promise.resolve({ runId }) });
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual(completed);
  const cached = await POST(new Request("http://localhost/api/player-matches", {
    method: "POST", body: JSON.stringify({ gameName: "Invented", tagLine: "NA1" }),
  }));
  expect(cached.status).toBe(200);
  expect(await cached.json()).toEqual(completed);

  for (const id of [-1, 1.5, "126697", Number.MAX_SAFE_INTEGER + 1, null]) {
    expect(() => parseLookup({ ...completed, matches: [{ ...completed.matches[0], endItemIds: [id] }] })).toThrow("INVALID_LOOKUP");
  }
});

it.each(["RUNNING", "FAILED"])("accepts an unresolved %s lookup without persisting player identity", async (status) => {
  const unresolved = { ...lookup, gameName: "", tagLine: "", status };
  expect(parseLookup(unresolved)).toEqual(unresolved);
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(unresolved)));
  const response = await GET(new Request("http://localhost"), { params: Promise.resolve({ runId }) });
  expect(response.status).toBe(200);
  expect(await response.json()).toEqual(unresolved);
});

it.each(["COMPLETE", "EMPTY", "PARTIAL"])("rejects missing identity on a verified %s result", (status) => {
  expect(() => parseLookup({ ...lookup, status, gameName: "", tagLine: "" })).toThrow("INVALID_LOOKUP");
});

it.each([
  { gameName: "", tagLine: "NA1" },
  { gameName: "Invented", tagLine: "" },
])("rejects partially missing identity: %j", (identity) => {
  for (const status of ["RUNNING", "FAILED"]) {
    expect(() => parseLookup({ ...lookup, status, ...identity })).toThrow("INVALID_LOOKUP");
  }
});

it("accepts twenty summaries but rejects oversized pages and unsupported queues", () => {
  const match = { matchId: "NA1_1", queueId: 420, participantId: 6, championName: "Garen", championId: 86,
    gameVersion: "16.17.1", endItemIds: [], position: "TOP", win: true, startedAtMs: 1,
    durationSeconds: 1800, kills: 8, deaths: 3, assists: 7, cs: 214, gold: 13800, timelineAvailable: false };
  expect(parseLookup({ ...lookup, queueId: 480, matches: Array(20).fill({ ...match, queueId: 480 }) }).matches).toHaveLength(20);
  expect(() => parseLookup({ ...lookup, matches: Array(21).fill(match) })).toThrow();
  expect(() => parseLookup({ ...lookup, queueId: 1700 })).toThrow();
  expect(() => parseLookup({ ...lookup, previousRunId: "../secret" })).toThrow();
});
it("forwards a supported queue and rejects provider pagination controls", async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ ...lookup, queueId: 480 }));
  vi.stubGlobal("fetch", fetcher);
  const post = (body: unknown) => POST(new Request("http://localhost", { method: "POST", body: JSON.stringify(body) }));
  expect((await post({ gameName: "Invented", tagLine: "NA1", queueId: 480 })).status).toBe(200);
  expect(await (fetcher.mock.calls[0][0] as Request).json()).toEqual({ gameName: "Invented", tagLine: "NA1", queueId: 480, platform: "NA1" });
  expect((await post({ gameName: "Invented", tagLine: "NA1", queueId: "480" })).status).toBe(400);
  expect((await post({ gameName: "Invented", tagLine: "NA1", count: 100 })).status).toBe(400);
});

it("validates older and refresh handles and sends empty POSTs to fixed backend paths", async () => {
  const { POST: older } = await import("../../app/api/player-matches/[runId]/older/route");
  const { POST: refresh } = await import("../../app/api/player-matches/[runId]/refresh/route");
  const fetcher = vi.fn().mockImplementation(async () => Response.json(lookup));
  vi.stubGlobal("fetch", fetcher);
  for (const [operation, handler] of [["older", older], ["refresh", refresh]] as const) {
    const context = { params: Promise.resolve({ runId }) };
    expect((await handler(new Request("http://localhost", { method: "POST" }), context)).status).toBe(200);
    const request = fetcher.mock.calls.at(-1)![0] as Request;
    expect(request.url).toBe(`http://127.0.0.1:8080/api/v1/player-matches/${runId}/${operation}`);
    expect(request.method).toBe("POST");
    expect((await handler(new Request("http://localhost", { method: "POST", body: '{"offset":100}' }), context)).status).toBe(400);
    expect((await handler(new Request("http://localhost", { method: "POST" }), { params: Promise.resolve({ runId: "../private" }) })).status).toBe(400);
  }
  expect(fetcher).toHaveBeenCalledTimes(2);
});


it("accepts mixed supported match queues only in all-queue history", () => {
  const match = { matchId: "NA1_21", queueId: 420, participantId: 6, championName: "Garen", championId: 86,
    gameVersion: "16.17.1", endItemIds: [], position: "TOP", win: true, startedAtMs: 1788890400000,
    durationSeconds: 1800, kills: 7, deaths: 2, assists: 9, cs: 180, gold: 12500, timelineAvailable: false };
  expect(parseLookup({ ...lookup, queueId: 0, matches: [match, { ...match, matchId: "NA1_22", queueId: 480 }] }).matches.map(m => m.queueId)).toEqual([420, 480]);
  expect(() => parseLookup({ ...lookup, queueId: 420, matches: [{ ...match, queueId: 480 }] })).toThrow();
  expect(() => parseLookup({ ...lookup, queueId: 0, matches: [{ ...match, queueId: 0 }] })).toThrow();
  expect(() => parseLookup({ ...lookup, queueId: 0, matches: [{ ...match, queueId: 1700 }] })).toThrow();
});
it("preserves nullable remake evidence without interpreting absence as a counted game", () => {
  const match = { matchId: "NA1_21", queueId: 420, participantId: 6, championName: "Garen", championId: 86,
    gameVersion: "16.17.1", endItemIds: [], position: "TOP", win: true, startedAtMs: 1788890400000,
    durationSeconds: 1800, kills: 7, deaths: 2, assists: 9, cs: 180, gold: 12500, timelineAvailable: false };
  for (const remake of [true, false, null]) {
    expect(parseLookup({ ...lookup, matches: [{ ...match, remake }] }).matches[0].remake).toBe(remake);
  }
  expect(parseLookup({ ...lookup, matches: [match] }).matches[0].remake).toBeNull();
  expect(() => parseLookup({ ...lookup, matches: [{ ...match, remake: "false" }] })).toThrow();
});

it("passes the selected region and rejects unsupported regions before contacting the backend", async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ ...lookup, platform: "KR" }));
  vi.stubGlobal("fetch", fetcher);
  const post = (platform: string) => POST(new Request("http://localhost/api/player-matches", { method: "POST", body: JSON.stringify({ gameName: "다른 이름", tagLine: "KR1", platform }) }));
  expect((await post("KR")).status).toBe(200);
  expect(await (fetcher.mock.calls[0][0] as Request).json()).toEqual({ gameName: "다른 이름", tagLine: "KR1", platform: "KR", queueId: 0 });
  expect((await post("OTHER")).status).toBe(400);
  expect(fetcher).toHaveBeenCalledTimes(1);
});

it("accepts regional match IDs and rejects a different platform inside a profile history", () => {
  const match = { matchId: "KR_21", queueId: 420, participantId: 6, championName: "Garen", championId: 86,
    gameVersion: "16.17.1", endItemIds: [], position: "TOP", win: true, startedAtMs: 1,
    durationSeconds: 1800, kills: 8, deaths: 3, assists: 7, cs: 214, gold: 13800, timelineAvailable: false };
  expect(parseLookup({ ...lookup, platform: "KR", matches: [match] }).matches[0].matchId).toBe("KR_21");
  expect(() => parseLookup({ ...lookup, platform: "EUW1", matches: [match] })).toThrow();
});
