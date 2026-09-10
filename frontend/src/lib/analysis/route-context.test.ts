import { expect, it } from "vitest";
import {
  parseAnalysisRouteContext,
  parseSearchParams,
  toAnalysisHref,
  toPanelCloseHref,
  withPanel,
  withRequestedLens,
} from "./route-context";
import type { AnalysisRouteContext } from "./types";

it("round-trips the complete investigation and return context", () => {
  const context: AnalysisRouteContext = {
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "investigate",
    selectedObjectId: "trn_123",
    interval: { startMs: 780275, endMs: 900291 },
    questionId: "advantage-conversion",
    evidenceRevision: "ev_123",
    requestedLens: "SEQUENCE",
    returnTarget: { mode: "review", objectId: "ep_123", beatId: "beat-4" },
    panel: "evidence",
  };

  expect(parseAnalysisRouteContext(toAnalysisHref(context))).toEqual(context);
});

it("rejects a partial interval and unknown enum values", () => {
  expect(() => parseSearchParams({ start: "1" })).toThrow(
    "INVALID_ROUTE_CONTEXT",
  );
  expect(() => parseSearchParams({ mode: "dashboard" })).toThrow(
    "INVALID_ROUTE_CONTEXT",
  );
});

it("omits Explore defaults without losing the focal participant", () => {
  expect(
    toAnalysisHref({
      matchId: "NA1_9000000001",
      focalParticipantId: 6,
      mode: "explore",
    }),
  ).toBe("/matches/NA1_9000000001?focus=6");
});

it("removes only the temporary panel", () => {
  const context: AnalysisRouteContext = {
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "investigate",
    selectedObjectId: "trn_123",
    interval: { startMs: 1, endMs: 2 },
    questionId: "what-changed",
    evidenceRevision: "ev_123",
    requestedLens: "STATE",
    panel: "evidence",
  };

  expect(withPanel(context, undefined)).toEqual({
    ...context,
    panel: undefined,
  });
});

it("accepts only strict positive integer focal and interval values", () => {
  expect(() => parseSearchParams({ focus: "6.5" })).toThrow(
    "INVALID_ROUTE_CONTEXT",
  );
  expect(() => parseSearchParams({ focus: "0" })).toThrow(
    "INVALID_ROUTE_CONTEXT",
  );
  expect(() => parseSearchParams({ focus: "11" })).toThrow(
    "INVALID_ROUTE_CONTEXT",
  );
  expect(() =>
    parseSearchParams({
      focus: "6",
      object: "trn_123",
      start: "-1",
      end: "2",
      question: "what-changed",
      evidence: "ev_123",
      lens: "STATE",
    }),
  ).toThrow("INVALID_ROUTE_CONTEXT");
  expect(() =>
    toAnalysisHref({
      matchId: "NA1_9000000001",
      focalParticipantId: 11,
      mode: "explore",
    }),
  ).toThrow("INVALID_ROUTE_CONTEXT");
});

it("preserves the backend receipt lens as a requestable fallback", () => {
  expect(
    parseSearchParams({
      focus: "6",
      object: "trn_123",
      start: "1",
      end: "2",
      question: "what-changed",
      evidence: "ev_123",
      lens: "RECEIPT",
    }).requestedLens,
  ).toBe("RECEIPT");
});

it("builds a panel close URL by removing only panel", () => {
  const context: AnalysisRouteContext = {
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "investigate",
    selectedObjectId: "trn_123",
    interval: { startMs: 1, endMs: 2 },
    questionId: "what-changed",
    evidenceRevision: "ev_123",
    requestedLens: "STATE",
    returnTarget: { mode: "review", objectId: "ep_123", beatId: "beat-4" },
    panel: "ask",
  };

  expect(toPanelCloseHref(context)).toBe(
    "/matches/NA1_9000000001?focus=6&mode=investigate&object=trn_123&start=1&end=2&question=what-changed&evidence=ev_123&lens=STATE&returnMode=review&returnObject=ep_123&returnBeat=beat-4",
  );
});

it("changes only the requested lens in canonical route context", () => {
  const context: AnalysisRouteContext = {
    matchId: "NA1_9000000001",
    focalParticipantId: 6,
    mode: "investigate",
    selectedObjectId: "trn_123",
    interval: { startMs: 1, endMs: 2 },
    questionId: "what-changed",
    evidenceRevision: "ev_123",
    requestedLens: "SEQUENCE",
    returnTarget: { mode: "review", objectId: "ep_123", beatId: "beat-4" },
    panel: "evidence",
  };

  expect(withRequestedLens(context, "TRANSFER")).toEqual({
    ...context,
    requestedLens: "TRANSFER",
  });
});
