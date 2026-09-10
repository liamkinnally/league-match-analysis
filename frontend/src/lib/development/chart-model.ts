import type { MatchDevelopmentSample, Metric } from "./types";

export const METRICS = {
  gold: {
    label: "Gold",
    unit: "gold",
    shortUnit: "g",
    diff: "goldDifference",
    absolute: "focalTotalGold",
  },
  cs: {
    label: "CS",
    unit: "CS",
    shortUnit: "CS",
    diff: "csDifference",
    absolute: "focalCs",
  },
  xp: {
    label: "XP",
    unit: "XP",
    shortUnit: "XP",
    diff: "xpDifference",
    absolute: "focalXp",
  },
} as const;
export const finite = (value: unknown): value is number =>
  typeof value === "number" && Number.isFinite(value);
export function timeLabel(ms: number, precise = false): string {
  if (!finite(ms)) return "Unavailable";
  const value = Math.trunc(ms);
  const label = `${Math.floor(value / 60000)}:${String(Math.floor((value % 60000) / 1000)).padStart(2, "0")}`;
  return precise ? `${label}.${String(value % 1000).padStart(3, "0")}` : label;
}
export function metricValue(
  sample: MatchDevelopmentSample,
  metric: Metric,
  compared: boolean,
) {
  const value = sample[METRICS[metric][compared ? "diff" : "absolute"]];
  return finite(value) ? value : null;
}
export function displayValue(
  value: number | null | undefined,
  metric: Metric,
  compared = true,
  short = false,
) {
  if (!finite(value)) return "Unavailable";
  const sign = compared && value > 0 ? "+" : value < 0 ? "−" : "";
  return `${sign}${Math.abs(value).toLocaleString("en-US")} ${METRICS[metric][short ? "shortUnit" : "unit"]}`;
}
export function intervalChangeLabel(
  start: number | null | undefined,
  end: number | null | undefined,
) {
  if (!finite(start) || !finite(end)) return "Change unavailable";
  if (start === end)
    return start > 0
      ? "Lead unchanged"
      : start < 0
        ? "Gap unchanged"
        : "Stayed level";
  if (end === 0) return start > 0 ? "Lead erased" : "Drew level";
  if (start <= 0 && end > 0) return "Took the lead";
  if (start >= 0 && end < 0) return "Fell behind";
  return end > 0
    ? end > start
      ? "Lead grew"
      : "Lead narrowed"
    : end > start
      ? "Gap narrowed"
      : "Gap widened";
}
export function chartRows(
  samples: MatchDevelopmentSample[],
  metric: Metric,
  compared = true,
) {
  const rows = samples.map((sample) => {
    const value = metricValue(sample, metric, compared);
    const focal = metricValue(sample, metric, false);
    return {
      timestampMs: sample.timestampMs,
      value,
      focal,
      comparison:
        compared && value !== null && focal !== null ? focal - value : null,
      isolated: false,
    };
  });
  return rows.map((row, i) => ({
    ...row,
    isolated:
      row.value !== null &&
      !finite(rows[i - 1]?.value) &&
      !finite(rows[i + 1]?.value),
  }));
}
export type ChartRow = ReturnType<typeof chartRows>[number];
export function chartScale(rows: ChartRow[]) {
  const values = rows.map((row) => row.value).filter(finite);
  const min = Math.min(0, ...values),
    max = Math.max(0, ...values);
  if (min === max)
    return {
      domain: [-1, 1] as [number, number],
      ticks: [-1, -0.5, 0, 0.5, 1],
    };
  const rough = (max - min) / 4,
    magnitude = 10 ** Math.floor(Math.log10(rough));
  const step =
    ([1, 2, 2.5, 5, 10].find((value) => value >= rough / magnitude) ?? 10) *
    magnitude;
  const first = Math.floor(min / step),
    last = Math.ceil(max / step);
  const ticks = Array.from({ length: last - first + 1 }, (_, i) =>
    Number(((first + i) * step).toPrecision(14)),
  );
  return { domain: [ticks[0], ticks.at(-1)!] as [number, number], ticks };
}
export function timeTicks(first: number, last: number) {
  if (first === last) return [first];
  const step =
    last - first > 20 * 60000
      ? 5 * 60000
      : last - first > 8 * 60000
        ? 2 * 60000
        : 60000;
  const ticks = [first];
  for (
    let time = Math.ceil((first + 1) / step) * step;
    time < last - step * 0.35;
    time += step
  )
    ticks.push(time);
  return [...ticks, last];
}
