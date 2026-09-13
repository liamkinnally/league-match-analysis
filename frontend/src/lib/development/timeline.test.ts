import { beforeEach, expect, it, vi } from "vitest";
import { GET, POST } from "../../app/api/matches/[matchId]/timeline/route";
vi.mock("server-only", () => ({}));
const params = { params: Promise.resolve({ matchId: "NA1_1" }) };
beforeEach(() => { vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080"); vi.stubEnv("PREVIEW_DATA_SOURCE", ""); });
it("uses a fixed timeline path, projects public state, and does not forward provider options", async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ matchId: "NA1_1", runId: null, status: "RUNNING", message: "secret", retryNotBefore: null, puuid: "secret" }));
  vi.stubGlobal("fetch", fetcher);
  const response = await POST(new Request("http://localhost", { method: "POST" }), params);
  expect(response.status).toBe(202);
  expect(await response.json()).toEqual({ matchId: "NA1_1", runId: null, status: "RUNNING", message: null, retryNotBefore: null });
  const request = fetcher.mock.calls[0][0] as Request;
  expect(request.url).toBe("http://127.0.0.1:8080/api/v1/matches/NA1_1/timeline");
  expect(request.method).toBe("POST");
  expect((await POST(new Request("http://localhost", { method: "POST", body: '{"host":"secret"}' }), params)).status).toBe(400);
  expect((await GET(new Request("http://localhost"), { params: Promise.resolve({ matchId: "../private" }) })).status).toBe(400);
  expect(fetcher).toHaveBeenCalledTimes(1);
});
it("fails closed on mismatched timeline identity and malformed status", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(Response.json({ matchId: "NA1_2", runId: null, status: "AVAILABLE" }))
    .mockResolvedValueOnce(Response.json({ matchId: "NA1_1", runId: null, status: "private" })));
  expect((await GET(new Request("http://localhost"), params)).status).toBe(502);
  expect((await GET(new Request("http://localhost"), params)).status).toBe(502);
});
it.each([GET, POST])("never contacts backend for synthetic preview timeline requests", async (handler) => {
  vi.stubEnv("VERCEL", "1"); vi.stubEnv("VERCEL_ENV", "preview"); vi.stubEnv("PREVIEW_DATA_SOURCE", "sample");
  const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
  expect((await handler(new Request("http://localhost", { method: handler === POST ? "POST" : "GET" }), params)).status).toBe(503);
  expect(fetcher).not.toHaveBeenCalled();
});
