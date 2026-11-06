import { beforeEach, expect, it, vi } from "vitest";
import { GET } from "../../app/api/leaderboard/route";
import { leaderboardFixture } from "./leaderboard.test-fixture";
vi.mock("server-only", () => ({}));
beforeEach(() => { vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080"); vi.stubEnv("SAMPLE_PREVIEW", "false"); });
it("reads only the cached fixed ladder and strips private fields", async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ ...leaderboardFixture, secret: "private", cutoffs: { ...leaderboardFixture.cutoffs, secret: "private", challenger: { ...leaderboardFixture.cutoffs.challenger, puuid: "private" } }, entries: leaderboardFixture.entries.map(row => ({ ...row, puuid: "private" })) })); vi.stubGlobal("fetch", fetcher);
  const response = await GET(new Request("http://localhost/api/leaderboard"));
  expect(response.status).toBe(200);
  expect(response.headers.get("Cache-Control")).toBe("no-store");
  expect(await response.json()).toEqual(leaderboardFixture);
  expect((fetcher.mock.calls[0][0] as Request).url).toBe("http://127.0.0.1:8080/api/v1/leaderboard/na");
});
it("sanitizes upstream errors and rejects malformed standings or caller selectors", async () => {
  const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
  expect((await GET(new Request("http://localhost/api/leaderboard?puuid=private"))).status).toBe(400);
  expect(fetcher).not.toHaveBeenCalled();
  for (const body of [ { message: "private token" }, { ...leaderboardFixture, entries: [{ ...leaderboardFixture.entries[0], wins: -1 }] }, { ...leaderboardFixture, entries: [{ ...leaderboardFixture.entries[0], tagLine: null }] }, { ...leaderboardFixture, entries: Array(301).fill(leaderboardFixture.entries[0]) } ]) {
    fetcher.mockResolvedValue(Response.json(body));
    const response = await GET(new Request("http://localhost/api/leaderboard"));
    expect(response.status).toBe(502); expect(await response.text()).not.toContain("private");
  }
  fetcher.mockResolvedValue(Response.json({ message: "private token" }, { status: 500 }));
  expect(await (await GET(new Request("http://localhost/api/leaderboard"))).json()).toEqual({ message: "Leaderboard is temporarily unavailable." });
});
