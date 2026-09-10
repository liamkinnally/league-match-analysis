import { beforeEach, expect, it, vi } from "vitest";
import { fixtureResponse } from "../../test/match-analysis-fixture";
import { loadMatchAnalysis } from "./analysis-source";
import { MatchNotFoundError } from "./backend-client";
import type { AnalysisRouteContext } from "./types";

vi.mock("server-only", () => ({}));

const ordinaryRoute: AnalysisRouteContext = {
  matchId: "NA1_9000000001",
  focalParticipantId: 6,
  mode: "explore",
};

const labRoute: AnalysisRouteContext = {
  matchId: "__lab_normal",
  focalParticipantId: 6,
  mode: "explore",
};

beforeEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

it("loads an ordinary match through the production backend source", async () => {
  const fetchMock = vi.fn().mockResolvedValue(Response.json(fixtureResponse));
  vi.stubGlobal("fetch", fetchMock);
  vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080");

  await expect(loadMatchAnalysis(ordinaryRoute)).resolves.toEqual(fixtureResponse);

  expect(fetchMock).toHaveBeenCalledTimes(1);
  const request = fetchMock.mock.calls[0][0] as Request;
  expect(request.url).toBe(
    "http://127.0.0.1:8080/api/matches/NA1_9000000001/analysis?focalParticipantId=6",
  );
  expect(request.cache).toBe("no-store");
});

it.each([
  ["missing runtime", { UI_LAB_ENABLED: "1" }],
  [
    "deployed production",
    {
      UI_LAB_ENABLED: "1",
      LEAGUE_ANALYSIS_RUNTIME: "deployed-production",
    },
  ],
  [
    "a known production host",
    {
      UI_LAB_ENABLED: "1",
      LEAGUE_ANALYSIS_RUNTIME: "verification",
      VERCEL_ENV: "production",
    },
  ],
])("fails closed for a reserved match ID in %s", async (_name, environment) => {
  for (const [key, value] of Object.entries(environment)) {
    vi.stubEnv(key, value);
  }
  const fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);

  await expect(loadMatchAnalysis(labRoute)).rejects.toBeInstanceOf(
    MatchNotFoundError,
  );
  expect(fetchMock).not.toHaveBeenCalled();
});

it.each(["local", "verification"])(
  "loads a validated lab response in the %s runtime",
  async (runtime) => {
    vi.stubEnv("UI_LAB_ENABLED", "1");
    vi.stubEnv("LEAGUE_ANALYSIS_RUNTIME", runtime);
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    await expect(loadMatchAnalysis(labRoute)).resolves.toMatchObject({
      match: { matchId: "__lab_normal" },
    });
    expect(fetchMock).not.toHaveBeenCalled();
  },
);

it("fails closed for an undeclared reserved match ID", async () => {
  vi.stubEnv("UI_LAB_ENABLED", "1");
  vi.stubEnv("LEAGUE_ANALYSIS_RUNTIME", "local");

  await expect(
    loadMatchAnalysis({ ...labRoute, matchId: "__lab_unknown" }),
  ).rejects.toBeInstanceOf(MatchNotFoundError);
});
