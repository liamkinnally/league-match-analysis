import "server-only";

import { parseDemoMatch, parseMatchDevelopment } from "./response-guards";
import type { DemoMatch, MatchDevelopment } from "./types";
import { backendFetch } from "../backend-transport";

export class DevelopmentNotFoundError extends Error {
  constructor() {
    super("MATCH_DEVELOPMENT_NOT_FOUND");
    this.name = "DevelopmentNotFoundError";
  }
}

export class DevelopmentRequestError extends Error {
  readonly status: number;

  constructor(status: number) {
    super("MATCH_DEVELOPMENT_REQUEST_FAILED");
    this.name = "DevelopmentRequestError";
    this.status = status;
  }
}

async function getJson(path: string): Promise<unknown> {
  const response = await backendFetch(path, { cache: "no-store", signal: AbortSignal.timeout(2_500) });
  if (response.status === 404) throw new DevelopmentNotFoundError();
  if (!response.ok) throw new DevelopmentRequestError(response.status);
  return response.json();
}

export function developmentLabEnabled(environment: NodeJS.ProcessEnv = process.env): boolean {
  const allowedRuntime = environment.LEAGUE_ANALYSIS_RUNTIME === "local"
    || environment.LEAGUE_ANALYSIS_RUNTIME === "verification";
  return environment.UI_LAB_ENABLED === "1"
    && allowedRuntime
    && environment.LEAGUE_ANALYSIS_RUNTIME !== "deployed-production";
}

export async function getMatchDevelopment(
  matchId: string,
  focus: number,
  compare?: number,
): Promise<MatchDevelopment> {
  if (developmentLabEnabled()) {
    const { resolveDevelopmentScenario } = await import("../../ui-lab/development-scenarios");
    const scenario = resolveDevelopmentScenario(matchId);
    if (scenario) return scenario;
  }
  const query = new URLSearchParams({ focus: String(focus) });
  if (compare !== undefined) query.set("compare", String(compare));
  const body = await getJson(
    `/api/v1/matches/${encodeURIComponent(matchId)}/development?${query}`,
  );
  return parseMatchDevelopment(body);
}

export async function getDemoMatch(): Promise<DemoMatch> {
  return parseDemoMatch(await getJson("/api/v1/demo"));
}
