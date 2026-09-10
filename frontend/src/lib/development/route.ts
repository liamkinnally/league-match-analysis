import type { DevelopmentInterval, Metric } from "./types";

export type DevelopmentSearchParams = Record<
  string,
  string | string[] | undefined
>;

const one = (value: string | string[] | undefined) =>
  Array.isArray(value) ? value[0] : value;

function participant(value: string | undefined): number | undefined {
  if (!value || !/^\d+$/.test(value)) return undefined;
  const parsed = Number(value);
  return parsed >= 1 && parsed <= 10 ? parsed : undefined;
}

function timestamp(value: string | undefined): number | undefined {
  if (!value || !/^\d+$/.test(value)) return undefined;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : undefined;
}

export function parseDevelopmentSearch(search: DevelopmentSearchParams) {
  const focusInput = one(search.focus);
  const compareInput = one(search.compare);
  const fromInput = one(search.from);
  const toInput = one(search.to);
  const focus = participant(focusInput) ?? 1;
  const compare = participant(compareInput);
  const from = timestamp(fromInput);
  const to = timestamp(toInput);
  const metricInput = one(search.metric);
  const metric: Metric =
    metricInput === "cs" || metricInput === "xp" ? metricInput : "gold";
  return {
    focus,
    metric,
    compare: compare === focus ? undefined : compare,
    interval:
      from !== undefined && to !== undefined && from < to
        ? { from, to }
        : undefined,
    hasInvalidSelection:
      (focusInput !== undefined && participant(focusInput) === undefined) ||
      (metricInput !== undefined &&
        !["gold", "cs", "xp"].includes(metricInput)) ||
      (compareInput !== undefined &&
        (compare === undefined || compare === focus)) ||
      ((fromInput !== undefined || toInput !== undefined) &&
        !(from !== undefined && to !== undefined && from < to)),
  };
}

export function developmentHref(
  matchId: string,
  focus: number,
  compare?: number,
  interval?: DevelopmentInterval,
  metric?: Metric,
): string {
  const query = new URLSearchParams({ focus: String(focus) });
  if (compare !== undefined) query.set("compare", String(compare));
  if (interval) {
    query.set("from", String(interval.from));
    query.set("to", String(interval.to));
  }
  if (metric && metric !== "gold") query.set("metric", metric);
  return `/matches/${encodeURIComponent(matchId)}/development?${query}`;
}
