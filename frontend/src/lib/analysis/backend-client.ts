import "server-only";

import { parseMatchAnalysisResponse } from "./response-guards";
import type { AnalysisRouteContext, MatchAnalysisResponse } from "./types";
import { backendFetch } from "../backend-transport";

export class MatchNotFoundError extends Error {
  constructor() {
    super("MATCH_NOT_FOUND");
    this.name = "MatchNotFoundError";
  }
}

export class StaleEvidenceError extends Error {
  readonly currentEvidenceRevision: string | null;

  constructor(currentEvidenceRevision: string | null) {
    super("STALE_EVIDENCE_REVISION");
    this.name = "StaleEvidenceError";
    this.currentEvidenceRevision = currentEvidenceRevision;
  }
}

export class MatchAnalysisRequestError extends Error {
  readonly status: number;

  constructor(status: number) {
    super("MATCH_ANALYSIS_REQUEST_FAILED");
    this.name = "MatchAnalysisRequestError";
    this.status = status;
  }
}

function analysisUrl(context: AnalysisRouteContext): string {
  const params = new URLSearchParams({
    focalParticipantId: String(context.focalParticipantId),
  });

  if (context.selectedObjectId !== undefined) {
    if (
      !context.interval ||
      !context.questionId ||
      !context.evidenceRevision ||
      !context.requestedLens
    ) {
      throw new Error("INVALID_ROUTE_CONTEXT");
    }
    params.set("objectId", context.selectedObjectId);
    params.set("intervalStartMs", String(context.interval.startMs));
    params.set("intervalEndMs", String(context.interval.endMs));
    params.set("questionId", context.questionId);
    params.set("evidenceRevision", context.evidenceRevision);
    params.set("requestedLens", context.requestedLens);
  } else if (
    context.interval ||
    context.questionId ||
    context.evidenceRevision ||
    context.requestedLens
  ) {
    throw new Error("INVALID_ROUTE_CONTEXT");
  }

  return `/api/matches/${encodeURIComponent(context.matchId)}/analysis?${params.toString()}`;
}

async function currentRevision(response: Response): Promise<string | null> {
  try {
    const body: unknown = await response.json();
    if (
      typeof body === "object" &&
      body !== null &&
      "currentEvidenceRevision" in body &&
      typeof body.currentEvidenceRevision === "string"
    ) {
      return body.currentEvidenceRevision;
    }
  } catch {
    // The typed status remains actionable even when the error body is malformed.
  }
  return null;
}

export async function getMatchAnalysis(
  context: AnalysisRouteContext,
): Promise<MatchAnalysisResponse> {
  const response = await backendFetch(analysisUrl(context), { cache: "no-store" });

  if (response.status === 404) throw new MatchNotFoundError();
  if (response.status === 409) {
    throw new StaleEvidenceError(await currentRevision(response));
  }
  if (!response.ok) throw new MatchAnalysisRequestError(response.status);

  const body: unknown = await response.json();
  return parseMatchAnalysisResponse(body);
}
