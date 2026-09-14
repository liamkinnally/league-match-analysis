import type { RunePerformance, RunePerformanceMetric } from "./types";
import { descriptionText } from "./urls";

const variablePattern = /@eogvar([123])@/g;
const unsupported = (id: string, label: string, reason: string): RunePerformanceMetric => ({ id, label, unit: "", availability: "unsupported", reason });

/** Only literal labels and direct raw-counter substitutions; this never evaluates provider expressions. */
export function runePerformanceMetrics(descriptors: unknown): RunePerformanceMetric[] {
  if (!Array.isArray(descriptors) || descriptors.length > 16 || descriptors.some(value => typeof value !== "string" || value.length > 1024)) return [unsupported("metadata", "Performance", "Counter descriptors are unavailable or unsupported.")];
  const lines = descriptors.flatMap((value: string) => value.split(/<br\s*\/?>|\r?\n/gi)).map(descriptionText).filter(Boolean).slice(0, 32);
  if (!lines.length) return [unsupported("metadata", "Performance", "This patch does not provide a counter descriptor.")];
  const meanings = new Map<number, Set<string>>();
  for (const line of lines) {
    for (const match of line.matchAll(variablePattern)) {
      const variable = Number(match[1]), labels = meanings.get(variable) ?? new Set<string>();
      labels.add(line.toLowerCase()); meanings.set(variable, labels);
    }
  }
  const unique = [...new Set(lines)];
  return unique.map((line, index): RunePerformanceMetric => {
    const id = `descriptor-${index + 1}`;
    const candidateLabel = line.split(":")[0].replace(/@[^@]*@/g, "").trim().slice(0, 120);
    const label = /[\p{L}\p{N}]/u.test(candidateLabel) ? candidateLabel : "Performance";
    const matches = [...line.matchAll(variablePattern)];
    if (matches.some(match => (meanings.get(Number(match[1]))?.size ?? 0) > 1)) return unsupported(id, label, "The source reuses this counter for different meanings.");
    const direct = line.match(/^([^@{}<>]+?):\s*@eogvar([123])@\s*(seconds?|secs?|s|%)?\s*$/i);
    if (!direct || matches.length !== 1 || /[{}]/.test(line)) return unsupported(id, label, "This counter uses an unsupported or ambiguous template.");
    const unitText = (direct[3] ?? "").toLowerCase(), metricLabel = direct[1].trim();
    // Time labels without an explicit seconds unit may encode a formatted duration.
    if (/\b(time|duration|uptime)\b/i.test(metricLabel) && !unitText && !/\bseconds?\b/i.test(metricLabel)) return unsupported(id, metricLabel, "The source does not establish this time counter's unit.");
    const unit = unitText === "%" ? "percent" : unitText || /\bseconds?\b/i.test(metricLabel) ? "seconds" : "";
    return { id, label: metricLabel, variable: Number(direct[2]) as 1 | 2 | 3, unit, availability: "available" };
  });
}
export function parseRunePerformance(perks: unknown): Record<string, RunePerformance> {
  if (!Array.isArray(perks)) return {};
  const result: Record<string, RunePerformance> = {};
  for (const value of perks) {
    if (!value || typeof value !== "object" || !Number.isSafeInteger(value.id) || value.id <= 0 || typeof value.name !== "string" || value.name.length > 128) continue;
    result[String(value.id)] = { name: descriptionText(value.name), metrics: runePerformanceMetrics(value.endOfGameStatDescs) };
  }
  return result;
}
