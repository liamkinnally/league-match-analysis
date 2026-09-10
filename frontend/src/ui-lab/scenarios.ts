import "server-only";

import {
  fixtureResponse,
  selectedResponse,
} from "../test/match-analysis-fixture";
import { parseMatchAnalysisResponse } from "../lib/analysis/response-guards";
import type {
  AnalysisRouteContext,
  MatchAnalysisResponse,
} from "../lib/analysis/types";

function cloneFixture(value: unknown): MatchAnalysisResponse {
  return structuredClone(parseMatchAnalysisResponse(value));
}

function matchesUnselectedExplore(context: AnalysisRouteContext): boolean {
  return (
    context.focalParticipantId === 6 &&
    context.mode === "explore" &&
    context.selectedObjectId === undefined &&
    context.interval === undefined &&
    context.questionId === undefined &&
    context.evidenceRevision === undefined &&
    context.requestedLens === undefined &&
    context.returnTarget === undefined &&
    context.panel === undefined
  );
}

function matchesSelection(
  context: AnalysisRouteContext,
  mode: "explore" | "investigate",
  requestedLens: "SEQUENCE" | "CHAMPION_TIMING",
): boolean {
  const selection = selectedResponse.active.context;
  return (
    context.focalParticipantId === 6 &&
    context.mode === mode &&
    context.selectedObjectId === selection.selectedObjectId &&
    context.interval?.startMs === selection.interval.startMs &&
    context.interval?.endMs === selection.interval.endMs &&
    context.questionId === selection.questionId &&
    context.evidenceRevision === selection.evidenceRevision &&
    context.requestedLens === requestedLens &&
    context.returnTarget === undefined &&
    context.panel === undefined
  );
}

function withMatchId(
  response: MatchAnalysisResponse,
  matchId: string,
): MatchAnalysisResponse {
  response.match.matchId = matchId;
  if (response.active) response.active.context.matchId = matchId;
  return response;
}

function scenarioCandidate(
  context: AnalysisRouteContext,
): MatchAnalysisResponse | undefined {
  switch (context.matchId) {
    case "__lab_normal": {
      if (!matchesUnselectedExplore(context)) return undefined;
      return withMatchId(cloneFixture(fixtureResponse), context.matchId);
    }
    case "__lab_investigation": {
      if (!matchesSelection(context, "investigate", "SEQUENCE")) {
        return undefined;
      }
      return withMatchId(cloneFixture(selectedResponse), context.matchId);
    }
    case "__lab_sparse": {
      if (!matchesUnselectedExplore(context)) return undefined;
      const response = withMatchId(cloneFixture(fixtureResponse), context.matchId);
      response.arc = {
        ...response.arc,
        transitions: [],
        eligibleUnselectedCount: 0,
        emptyReason: "INSUFFICIENT_EVIDENCE",
      };
      response.review = {
        ...response.review,
        episodes: [],
        learningOrder: [],
        chronologicalOrder: [],
        temporalCues: {},
      };
      response.active = null;
      return response;
    }
    case "__lab_unsupported": {
      if (!matchesSelection(context, "explore", "CHAMPION_TIMING")) {
        return undefined;
      }
      const response = withMatchId(cloneFixture(selectedResponse), context.matchId);
      if (!response.active) return undefined;
      response.active.context.selectedLens = "RECEIPT";
      response.active.context.availableLenses = [
        "SEQUENCE",
        "STATE",
        "TRANSFER",
        "RECEIPT",
      ];
      response.active.lens = {
        type: "RECEIPT",
        receipt: response.active.receipt,
        claims: response.active.claims,
      };
      response.active.limitations = [
        ...response.active.limitations,
        "REQUESTED_LENS_UNAVAILABLE",
      ];
      return response;
    }
  }
  return undefined;
}

export function resolveUiLabScenario(
  context: AnalysisRouteContext,
): MatchAnalysisResponse | undefined {
  const candidate = scenarioCandidate(context);
  return candidate === undefined
    ? undefined
    : parseMatchAnalysisResponse(candidate);
}
