import { beforeEach, expect, it, vi } from "vitest";
import { POST } from "../../app/api/player-matches/route";
import { parseLookup } from "./types";
import { GET } from "../../app/api/player-matches/[runId]/route";

vi.mock("server-only", () => ({}));

const runId = "00000000-0000-0000-0000-000000000001";
const lookup = { runId, gameName: "Invented", tagLine: "NA1", status: "RUNNING", message: null, retryNotBefore: null, matches: [] };
beforeEach(() => vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080"));

it("forwards only validated Riot ID fields and strips private upstream data", async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ ...lookup, resolvedPuuid: "private-puuid", raw: "private-body" }, { status: 202 }));
  vi.stubGlobal("fetch", fetcher);
  const response = await POST(new Request("http://localhost/api/player-matches", { method: "POST", headers: { "X-Forwarded-For": "spoof", "Content-Type": "application/json" }, body: JSON.stringify({ gameName: " Invented ", tagLine: "NA1", apiKey: "secret" }) }));
  expect(response.status).toBe(202);
  expect(await response.json()).toEqual(lookup);
  const request = fetcher.mock.calls[0][0] as Request;
  expect(request.headers.get("content-type")).toBe("application/json");
  expect(request.headers.has("authorization")).toBe(false);
  expect(await request.text()).toBe('{"gameName":"Invented","tagLine":"NA1"}');
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
  const result = parseLookup({ ...lookup, matches: [{ matchId: "NA1_7000000002", participantId: 6,
    championName: "Garen", championId: 86, gameVersion: "16.17.1", endItemIds: [3071, 3047, 3053, 6333, 3065, 0, 3364],
    position: "TOP", win: true, startedAtMs: 1788890400000, durationSeconds: 1800, kills: 8, deaths: 3, assists: 7,
    cs: 214, gold: 13800, timelineAvailable: true, raw: "private" }] });
  expect(result.matches[0].gameVersion).toBe("16.17.1");
  expect(result.matches[0].endItemIds).toEqual([3071, 3047, 3053, 6333, 3065, 0, 3364]);
  expect(result.matches[0]).not.toHaveProperty("raw");
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
