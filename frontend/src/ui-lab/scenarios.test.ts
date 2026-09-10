import { afterEach, expect, it, vi } from "vitest";
import {
  fixtureResponse,
  selectedResponse,
} from "../test/match-analysis-fixture";
import { resolveUiLabScenario } from "./scenarios";
import type { AnalysisRouteContext } from "../lib/analysis/types";

vi.mock("server-only", () => ({}));

const selection = selectedResponse.active!.context;

function routeFor(matchId: string): AnalysisRouteContext {
  if (matchId === "__lab_investigation") {
    return {
      matchId,
      focalParticipantId: 6,
      mode: "investigate",
      selectedObjectId: String(selection.selectedObjectId),
      interval: selection.interval,
      questionId: selection.questionId,
      evidenceRevision: selection.evidenceRevision,
      requestedLens: "SEQUENCE",
    };
  }
  if (matchId === "__lab_unsupported") {
    return {
      matchId,
      focalParticipantId: 6,
      mode: "explore",
      selectedObjectId: String(selection.selectedObjectId),
      interval: selection.interval,
      questionId: selection.questionId,
      evidenceRevision: selection.evidenceRevision,
      requestedLens: "CHAMPION_TIMING",
    };
  }
  return { matchId, focalParticipantId: 6, mode: "explore" };
}

afterEach(() => {
  fixtureResponse.match.durationMs = 1_800_000;
});

it.each([
  ["__lab_normal", "normal whole-match Explore"],
  ["__lab_investigation", "selected evidence-rich Investigation"],
  ["__lab_sparse", "sparse or empty evidence"],
  ["__lab_unsupported", "requested-lens Receipt fallback"],
])("resolves %s as %s", (matchId) => {
  const response = resolveUiLabScenario(routeFor(matchId));

  expect(response?.match.matchId).toBe(matchId);
});

it("returns undefined for an undeclared scenario", () => {
  expect(resolveUiLabScenario(routeFor("__lab_unknown"))).toBeUndefined();
});

it("returns undefined for an undeclared route context", () => {
  expect(
    resolveUiLabScenario({
      ...routeFor("__lab_investigation"),
      requestedLens: "MAP",
    }),
  ).toBeUndefined();
});

it("rejects a malformed synthetic response through the runtime parser", () => {
  fixtureResponse.match.durationMs = -1;

  expect(() => resolveUiLabScenario(routeFor("__lab_normal"))).toThrow(
    "INVALID_MATCH_ANALYSIS_RESPONSE",
  );
});

it("keeps sparse evidence truthful and unselected", () => {
  const response = resolveUiLabScenario(routeFor("__lab_sparse"));

  expect(response?.arc).toMatchObject({
    transitions: [],
    eligibleUnselectedCount: 0,
    emptyReason: "INSUFFICIENT_EVIDENCE",
  });
  expect(response?.review.episodes).toEqual([]);
  expect(response?.active).toBeNull();
});

it("represents an unavailable requested lens with the Receipt fallback", () => {
  const response = resolveUiLabScenario(routeFor("__lab_unsupported"));

  expect(response?.active?.lens.type).toBe("RECEIPT");
  expect(response?.active?.context.selectedLens).toBe("RECEIPT");
  expect(response?.active?.limitations).toContain("REQUESTED_LENS_UNAVAILABLE");
  expect(response?.active?.context.availableLenses).not.toContain(
    "CHAMPION_TIMING",
  );
});
