import { render, screen } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { fixtureResponse } from "../../../test/match-analysis-fixture";
import MatchPage from "./page";

vi.mock("server-only", () => ({}));

const matchId = "NA1_9000000001";
const staleQuery = {
  focus: "6", mode: "investigate", object: "trn_000000000000000000000002",
  start: "780275", end: "900291", question: "advantage-conversion",
  evidence: `ev_${"0".repeat(64)}`, lens: "STATE", panel: "evidence",
  returnMode: "review", returnObject: "ep_000000000000000000000002", returnBeat: "beat-4",
};

beforeEach(() => vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080"));

it.each([
  ["investigate", "review", `/matches/${matchId}?focus=6&mode=review`],
  ["investigate", "explore", `/matches/${matchId}?focus=6`],
  ["investigate", undefined, `/matches/${matchId}?focus=6`],
  ["review", "explore", `/matches/${matchId}?focus=6&mode=review`],
  ["explore", "review", `/matches/${matchId}?focus=6`],
])("offers a fresh safe parent from stale %s/%s context", async (mode, returnMode, href) => {
  const fetchMock = vi.fn().mockResolvedValue(Response.json({
    code: "STALE_EVIDENCE_REVISION", currentEvidenceRevision: `ev_${"f".repeat(64)}`,
  }, { status: 409 }));
  vi.stubGlobal("fetch", fetchMock);
  const query = { ...staleQuery, mode, returnMode,
    returnObject: returnMode ? staleQuery.returnObject : undefined,
    returnBeat: returnMode ? staleQuery.returnBeat : undefined,
  };

  render(await MatchPage({ params: Promise.resolve({ matchId }), searchParams: Promise.resolve(query) }));

  expect(screen.getByRole("heading", { name: "Match evidence changed" })).toBeVisible();
  expect(screen.getByText(/refresh this analysis before continuing/i)).toBeVisible();
  expect(screen.getByRole("link", { name: "Refresh analysis" })).toHaveAttribute("href", href);
  expect(screen.queryByTestId("analysis-stage")).toBeNull();
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it("fetches current evidence without stale claims when the refresh destination is loaded", async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(Response.json({ currentEvidenceRevision: `ev_${"f".repeat(64)}` }, { status: 409 }))
    .mockResolvedValueOnce(Response.json(fixtureResponse));
  vi.stubGlobal("fetch", fetchMock);
  const { unmount } = render(await MatchPage({
    params: Promise.resolve({ matchId }), searchParams: Promise.resolve(staleQuery),
  }));
  const href = screen.getByRole("link", { name: "Refresh analysis" }).getAttribute("href")!;
  const url = new URL(href, "http://localhost");
  unmount();

  render(await MatchPage({ params: Promise.resolve({ matchId }),
    searchParams: Promise.resolve(Object.fromEntries(url.searchParams)),
  }));

  expect(screen.getByRole("heading", { name: "Case 1 of 4" })).toBeVisible();
  const request = fetchMock.mock.calls.at(-1)![0] as Request;
  expect(request.url).toBe(
    "http://127.0.0.1:8080/api/matches/NA1_9000000001/analysis?focalParticipantId=6",
  );
  expect(request.cache).toBe("no-store");
});

it("keeps unrelated backend failures in the generic error boundary", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({}, { status: 500 })));
  await expect(MatchPage({ params: Promise.resolve({ matchId }),
    searchParams: Promise.resolve({ focus: "6" }),
  })).rejects.toThrow("MATCH_ANALYSIS_REQUEST_FAILED");
});
