import { beforeEach, expect, it, vi } from "vitest";
import { fixtureResponse, selectedResponse } from "../../test/match-analysis-fixture";
import {
  getMatchAnalysis,
  MatchNotFoundError,
  StaleEvidenceError,
} from "./backend-client";
import type { AnalysisRouteContext } from "./types";

vi.mock("server-only", () => ({}));

beforeEach(() => {
  vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080/");
});

it("fetches the exact calm backend URL without credentials or caching", async () => {
  const fetchMock = vi.fn().mockResolvedValue(Response.json(fixtureResponse));
  vi.stubGlobal("fetch", fetchMock);

  await expect(
    getMatchAnalysis({
      matchId: "NA1_9000000001",
      focalParticipantId: 6,
      mode: "explore",
    }),
  ).resolves.toEqual(fixtureResponse);

  const request = fetchMock.mock.calls[0][0] as Request;
  expect(request.url).toBe("http://127.0.0.1:8080/api/matches/NA1_9000000001/analysis?focalParticipantId=6");
  expect(request.cache).toBe("no-store");
});

it("maps the canonical selected context to the exact backend query", async () => {
  const fetchMock = vi.fn().mockResolvedValue(Response.json(selectedResponse));
  vi.stubGlobal("fetch", fetchMock);
  const route: AnalysisRouteContext = {
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "investigate",
    selectedObjectId: "trn_000000000000000000000002",
    interval: { startMs: 780_275, endMs: 900_291 },
    questionId: "advantage-conversion",
    evidenceRevision: `ev_${"1".repeat(64)}`,
    requestedLens: "SEQUENCE",
    returnTarget: { mode: "review", objectId: "ep_123", beatId: "beat-4" },
    panel: "evidence",
  };

  await getMatchAnalysis(route);

  const request = fetchMock.mock.calls[0][0] as Request;
  expect(request.url).toBe(
    `http://127.0.0.1:8080/api/matches/NA1_9000000001/analysis?focalParticipantId=6&objectId=trn_000000000000000000000002&intervalStartMs=780275&intervalEndMs=900291&questionId=advantage-conversion&evidenceRevision=ev_${"1".repeat(64)}&requestedLens=SEQUENCE`,
  );
});

it("maps 404 responses to MatchNotFoundError", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(
      Response.json({ code: "MATCH_NOT_FOUND" }, { status: 404 }),
    ),
  );

  await expect(
    getMatchAnalysis({
      matchId: "missing",
      focalParticipantId: 6,
      mode: "explore",
    }),
  ).rejects.toBeInstanceOf(MatchNotFoundError);
});

it("maps 409 responses to StaleEvidenceError with the current revision", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(
      Response.json(
        {
          code: "STALE_EVIDENCE_REVISION",
          message: "The match evidence changed.",
          currentEvidenceRevision: "ev_current",
        },
        { status: 409 },
      ),
    ),
  );

  const error = await getMatchAnalysis({
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "explore",
  }).catch((caught: unknown) => caught);

  expect(error).toBeInstanceOf(StaleEvidenceError);
  expect(error).toMatchObject({ currentEvidenceRevision: "ev_current" });
});
