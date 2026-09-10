import { beforeEach, expect, it, vi } from "vitest";
import { demoFixture, developmentFixture } from "../../test/match-development-fixture";
import { getDemoMatch, getMatchDevelopment } from "./backend-client";

vi.mock("server-only", () => ({}));

beforeEach(() => vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080/"));

it("fetches the exact development comparison without caching", async () => {
  const fetchMock = vi.fn().mockResolvedValue(Response.json(developmentFixture));
  vi.stubGlobal("fetch", fetchMock);

  await expect(getMatchDevelopment("NA1_7000000001", 6, 1)).resolves.toEqual(developmentFixture);
  const request = fetchMock.mock.calls[0][0] as Request;
  expect(request.url).toBe("http://127.0.0.1:8080/api/v1/matches/NA1_7000000001/development?focus=6&compare=1");
  expect(request.cache).toBe("no-store");
  expect(request.signal).toBeInstanceOf(AbortSignal);
});

it("loads the marked demo locator", async () => {
  const fetchMock = vi.fn().mockResolvedValue(Response.json(demoFixture));
  vi.stubGlobal("fetch", fetchMock);

  await expect(getDemoMatch()).resolves.toEqual(demoFixture);
  const request = fetchMock.mock.calls[0][0] as Request;
  expect(request.url).toBe("http://127.0.0.1:8080/api/v1/demo");
  expect(request.cache).toBe("no-store");
});

it("loads deterministic development scenarios only in an enabled local or verification runtime", async () => {
  vi.stubEnv("UI_LAB_ENABLED", "1");
  vi.stubEnv("LEAGUE_ANALYSIS_RUNTIME", "verification");
  const fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);

  await expect(getMatchDevelopment("__lab_development", 6, 1)).resolves.toMatchObject({
    matchId: "__lab_development",
    summary: { focusParticipantId: 6, compareParticipantId: 1 },
  });
  expect(fetchMock).not.toHaveBeenCalled();
});

it("does not expose deterministic development scenarios in deployed production", async () => {
  vi.stubEnv("UI_LAB_ENABLED", "1");
  vi.stubEnv("LEAGUE_ANALYSIS_RUNTIME", "deployed-production");
  const fetchMock = vi.fn().mockResolvedValue(Response.json(developmentFixture));
  vi.stubGlobal("fetch", fetchMock);

  await getMatchDevelopment("__lab_development", 6, 1);

  const request = fetchMock.mock.calls[0][0] as Request;
  expect(request.url).toBe("http://127.0.0.1:8080/api/v1/matches/__lab_development/development?focus=6&compare=1");
});
