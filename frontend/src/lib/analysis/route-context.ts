import type {
  AnalysisMode,
  AnalysisPanel,
  AnalysisReturnTarget,
  AnalysisRouteContext,
  RequestedLens,
  ReviewEpisode,
} from "./types";

export type AnalysisSearchParams = Record<
  string,
  string | string[] | undefined
>;

type ParsedSearchParams = Omit<AnalysisRouteContext, "matchId">;

const MODES = new Set<AnalysisMode>(["explore", "review", "investigate"]);
const PANELS = new Set<AnalysisPanel>(["evidence", "ask"]);
const REQUESTED_LENSES = new Set<RequestedLens>([
  "MAP",
  "SEQUENCE",
  "STATE",
  "CHAMPION_TIMING",
  "TRANSFER",
  "RECEIPT",
]);

function invalidRouteContext(): never {
  throw new Error("INVALID_ROUTE_CONTEXT");
}

function singleValue(
  params: AnalysisSearchParams | URLSearchParams,
  key: string,
): string | undefined {
  if (params instanceof URLSearchParams) {
    const values = params.getAll(key);
    if (values.length > 1) invalidRouteContext();
    return values[0];
  }

  const value = params[key];
  if (Array.isArray(value)) invalidRouteContext();
  return value;
}

function strictInteger(
  value: string | undefined,
  minimum: number,
  maximum = Number.MAX_SAFE_INTEGER,
): number {
  if (value === undefined || !/^(0|[1-9]\d*)$/.test(value)) {
    return invalidRouteContext();
  }
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < minimum || parsed > maximum) {
    return invalidRouteContext();
  }
  return parsed;
}

function optionalEnum<T extends string>(
  value: string | undefined,
  values: ReadonlySet<T>,
): T | undefined {
  if (value === undefined) return undefined;
  if (!values.has(value as T)) return invalidRouteContext();
  return value as T;
}

function optionalText(value: string | undefined): string | undefined {
  if (value === undefined) return undefined;
  if (value.trim() === "") return invalidRouteContext();
  return value;
}

export function parseSearchParams(
  params: AnalysisSearchParams | URLSearchParams,
): ParsedSearchParams {
  const focus = singleValue(params, "focus");
  const mode =
    optionalEnum(singleValue(params, "mode"), MODES) ?? "explore";
  const start = singleValue(params, "start");
  const end = singleValue(params, "end");

  if ((start === undefined) !== (end === undefined)) {
    return invalidRouteContext();
  }

  const selectedObjectId = optionalText(singleValue(params, "object"));
  const questionId = optionalText(singleValue(params, "question"));
  const evidenceRevision = optionalText(singleValue(params, "evidence"));
  const requestedLens = optionalEnum(
    singleValue(params, "lens"),
    REQUESTED_LENSES,
  );
  const hasSelectionContext = [
    selectedObjectId,
    start,
    end,
    questionId,
    evidenceRevision,
    requestedLens,
  ].some((value) => value !== undefined);
  const hasCompleteSelectionContext = [
    selectedObjectId,
    start,
    end,
    questionId,
    evidenceRevision,
    requestedLens,
  ].every((value) => value !== undefined);

  if (hasSelectionContext !== hasCompleteSelectionContext) {
    return invalidRouteContext();
  }

  const returnMode = optionalEnum(
    singleValue(params, "returnMode"),
    MODES,
  );
  const returnObjectId = optionalText(singleValue(params, "returnObject"));
  const returnBeatId = optionalText(singleValue(params, "returnBeat"));
  if ((returnObjectId !== undefined || returnBeatId !== undefined) && !returnMode) {
    return invalidRouteContext();
  }

  const result: ParsedSearchParams = {
    focalParticipantId: strictInteger(focus, 1, 10),
    mode,
  };

  if (hasCompleteSelectionContext) {
    const startMs = strictInteger(start, 0);
    const endMs = strictInteger(end, 0);
    if (endMs < startMs) return invalidRouteContext();
    result.selectedObjectId = selectedObjectId;
    result.interval = { startMs, endMs };
    result.questionId = questionId;
    result.evidenceRevision = evidenceRevision;
    result.requestedLens = requestedLens;
  }

  if (returnMode) {
    const returnTarget: AnalysisReturnTarget = { mode: returnMode };
    if (returnObjectId) returnTarget.objectId = returnObjectId;
    if (returnBeatId) returnTarget.beatId = returnBeatId;
    result.returnTarget = returnTarget;
  }

  const panel = optionalEnum(singleValue(params, "panel"), PANELS);
  if (panel) result.panel = panel;

  return result;
}

export function parseAnalysisRouteContext(href: string): AnalysisRouteContext;
export function parseAnalysisRouteContext(
  matchId: string,
  params: AnalysisSearchParams | URLSearchParams,
): AnalysisRouteContext;
export function parseAnalysisRouteContext(
  hrefOrMatchId: string,
  params?: AnalysisSearchParams | URLSearchParams,
): AnalysisRouteContext {
  if (params) {
    if (hrefOrMatchId.trim() === "") return invalidRouteContext();
    return { matchId: hrefOrMatchId, ...parseSearchParams(params) };
  }

  const url = new URL(hrefOrMatchId, "http://league-analysis.local");
  const match = /^\/matches\/([^/]+)$/.exec(url.pathname);
  if (!match) return invalidRouteContext();
  const matchId = decodeURIComponent(match[1]);
  if (matchId.trim() === "") return invalidRouteContext();
  return { matchId, ...parseSearchParams(url.searchParams) };
}

export function toAnalysisHref(context: AnalysisRouteContext): string {
  if (context.matchId.trim() === "") return invalidRouteContext();
  strictInteger(String(context.focalParticipantId), 1, 10);
  const params = new URLSearchParams();
  params.set("focus", String(context.focalParticipantId));
  if (context.mode !== "explore") params.set("mode", context.mode);

  if (context.selectedObjectId !== undefined) {
    if (
      !context.interval ||
      !context.questionId ||
      !context.evidenceRevision ||
      !context.requestedLens
    ) {
      return invalidRouteContext();
    }
    if (
      !Number.isSafeInteger(context.interval.startMs) ||
      !Number.isSafeInteger(context.interval.endMs) ||
      context.interval.startMs < 0 ||
      context.interval.endMs < context.interval.startMs
    ) {
      return invalidRouteContext();
    }
    params.set("object", context.selectedObjectId);
    params.set("start", String(context.interval.startMs));
    params.set("end", String(context.interval.endMs));
    params.set("question", context.questionId);
    params.set("evidence", context.evidenceRevision);
    params.set("lens", context.requestedLens);
  } else if (
    context.interval ||
    context.questionId ||
    context.evidenceRevision ||
    context.requestedLens
  ) {
    return invalidRouteContext();
  }

  if (context.returnTarget) {
    params.set("returnMode", context.returnTarget.mode);
    if (context.returnTarget.objectId) {
      params.set("returnObject", context.returnTarget.objectId);
    }
    if (context.returnTarget.beatId) {
      params.set("returnBeat", context.returnTarget.beatId);
    }
  }
  if (context.panel) params.set("panel", context.panel);

  return `/matches/${encodeURIComponent(context.matchId)}?${params.toString()}`;
}

export function withPanel(
  context: AnalysisRouteContext,
  panel: AnalysisPanel | undefined,
): AnalysisRouteContext {
  return { ...context, panel };
}

export function withRequestedLens(
  context: AnalysisRouteContext,
  requestedLens: RequestedLens,
): AnalysisRouteContext {
  return { ...context, requestedLens };
}

export function toPanelCloseHref(context: AnalysisRouteContext): string {
  return toAnalysisHref(withPanel(context, undefined));
}

function reviewCrossModeContext(
  episode: ReviewEpisode,
  route: AnalysisRouteContext,
  mode: "explore" | "investigate",
  returnBeatId?: string,
): AnalysisRouteContext {
  const restoredBeatId =
    returnBeatId ??
    (route.returnTarget?.mode === "review" &&
    route.returnTarget.objectId === episode.id
      ? route.returnTarget.beatId
      : undefined);
  return {
    ...route,
    mode,
    selectedObjectId: episode.transitionId,
    interval: route.interval ?? episode.interval,
    questionId: route.questionId ?? episode.questionId,
    requestedLens: route.requestedLens ?? "SEQUENCE",
    returnTarget: {
      mode: "review",
      objectId: episode.id,
      beatId: restoredBeatId,
    },
    panel: undefined,
  };
}

export function exploreThisMomentHref(
  episode: ReviewEpisode,
  route: AnalysisRouteContext,
): string {
  const href = toAnalysisHref(
    reviewCrossModeContext(episode, route, "explore"),
  );
  const url = new URL(href, "http://league-analysis.local");
  url.searchParams.set("mode", "explore");
  return `${url.pathname}?${url.searchParams.toString()}`;
}

export function investigateHref(
  episode: ReviewEpisode,
  beatId: string,
  route: AnalysisRouteContext,
): string {
  return toAnalysisHref(
    reviewCrossModeContext(episode, route, "investigate", beatId),
  );
}
