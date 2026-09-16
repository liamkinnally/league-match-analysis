import { beforeEach, expect, it, vi } from "vitest";
import { GET } from "./route";
vi.mock("server-only", () => ({}));
beforeEach(() => vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080"));
const suggestion = { gameName: "다른 이름", tagLine: "KR1", platform: "KR", profileIconId: 29, summonerLevel: 200 };
it("forwards the encoded query and selected platform while stripping private fields", async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ suggestions: [{ ...suggestion, puuid: "private" }] }));
  vi.stubGlobal("fetch", fetcher);
  const result = await GET(new Request("http://localhost/api/player-suggestions?platform=KR&q=%EB%8B%A4%EB%A5%B8"));
  expect(await result.json()).toEqual({ suggestions: [suggestion] });
  expect((fetcher.mock.calls[0][0] as Request).url).toBe("http://127.0.0.1:8080/api/v1/player-suggestions?platform=KR&q=%EB%8B%A4%EB%A5%B8");
});
it("rejects invalid parameters without a backend request", async () => {
  const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
  for (const query of ["platform=OTHER&q=a", "platform=NA1&platform=KR&q=a", "q=a&q=b", "q=a&puuid=secret", `q=${"a".repeat(82)}`]) {
    expect((await GET(new Request(`http://localhost/api/player-suggestions?${query}`))).status).toBe(400);
  }
  expect(fetcher).not.toHaveBeenCalled();
});
it("rejects oversized and cross-region suggestions rather than mislabeling profiles", async () => {
  for (const suggestions of [Array(6).fill(suggestion), [{ ...suggestion, platform: "NA1" }]]) {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ suggestions })));
    expect((await GET(new Request("http://localhost/api/player-suggestions?platform=KR&q=a"))).status).toBe(503);
  }
});
