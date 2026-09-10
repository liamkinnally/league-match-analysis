import "server-only";

import { getMatchAnalysis, MatchNotFoundError } from "./backend-client";
import type { AnalysisRouteContext, MatchAnalysisResponse } from "./types";

const LAB_MATCH_PREFIX = "__lab_";

function uiLabAllowed(environment: NodeJS.ProcessEnv = process.env): boolean {
  const runtimeAllowed =
    environment.LEAGUE_ANALYSIS_RUNTIME === "local" ||
    environment.LEAGUE_ANALYSIS_RUNTIME === "verification";
  return (
    environment.UI_LAB_ENABLED === "1" &&
    runtimeAllowed &&
    environment.LEAGUE_ANALYSIS_RUNTIME !== "deployed-production" &&
    environment.VERCEL_ENV !== "production"
  );
}

export async function loadMatchAnalysis(
  context: AnalysisRouteContext,
): Promise<MatchAnalysisResponse> {
  if (!context.matchId.startsWith(LAB_MATCH_PREFIX)) {
    return getMatchAnalysis(context);
  }
  if (!uiLabAllowed()) throw new MatchNotFoundError();

  const { resolveUiLabScenario } = await import("../../ui-lab/scenarios");
  const response = resolveUiLabScenario(context);
  if (!response) throw new MatchNotFoundError();
  return response;
}
