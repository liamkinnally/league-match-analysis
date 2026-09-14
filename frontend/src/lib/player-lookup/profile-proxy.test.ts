import { beforeEach, expect, it, vi } from "vitest";
import { GET } from "../../app/api/player-matches/[runId]/profile/route";
import { POST } from "../../app/api/player-matches/[runId]/recent-record/route";
import { profileFixture } from "./profile.test-fixture";
vi.mock("server-only", () => ({}));
const runId = "00000000-0000-0000-0000-000000000001", context = { params: Promise.resolve({ runId }) };
beforeEach(() => { vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080"); vi.stubEnv("SAMPLE_PREVIEW", "false"); });
it("reuses the backend transport and strips private upstream profile fields", async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ ...profileFixture, puuid: "private" })); vi.stubGlobal("fetch", fetcher);
  const response = await GET(new Request("http://localhost/api/player-matches/profile?cursor=YWJj"), context);
  expect(response.status).toBe(200); expect(response.headers.get("Cache-Control")).toBe("no-store");
  expect(await response.json()).toEqual(profileFixture);
  const request = fetcher.mock.calls[0][0] as Request;
  expect(request.url).toBe(`http://127.0.0.1:8080/api/v1/player-matches/${runId}/profile?cursor=YWJj`);
});
it("rejects caller identity/provider paths and bodies before scheduling recent work", async () => {
  const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
  expect((await GET(new Request("http://localhost?puuid=private"), context)).status).toBe(400);
  expect((await GET(new Request("http://localhost?cursor=https://evil.test"), context)).status).toBe(400);
  expect((await POST(new Request("http://localhost", { method: "POST", body: '{"limit":1000}' }), context)).status).toBe(400);
  expect(fetcher).not.toHaveBeenCalled();
});
it("passes admitted empty POST and preserves safe cooldown metadata without upstream errors", async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ message: "private credentials", retryNotBefore: "2026-09-14T12:00:00Z" }, { status: 429, headers: { "Retry-After": "900" } })); vi.stubGlobal("fetch", fetcher);
  const response = await POST(new Request("http://localhost", { method: "POST" }), context);
  expect(response.status).toBe(429); expect(response.headers.get("Retry-After")).toBe("900");
  const body = await response.json(); expect(body.retryNotBefore).toBe("2026-09-14T12:00:00Z"); expect(body.message).not.toContain("private");
  expect((fetcher.mock.calls[0][0] as Request).method).toBe("POST");
});
