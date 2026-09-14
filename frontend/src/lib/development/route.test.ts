import { describe, expect, it } from "vitest";
import { developmentHref, parseDevelopmentSearch } from "./route";

describe("final-state URL selection", () => {
  it("preserves independent rune participant and analysis selections", () => {
    const href = developmentHref("NA1_123", 6, 1, { from: 505210, to: 600000 }, "xp", { finalView: "runes", runeParticipant: 2 });
    expect(href).toBe("/matches/NA1_123/development?focus=6&compare=1&from=505210&to=600000&metric=xp&finalView=runes&runeParticipant=2");
    expect(parseDevelopmentSearch({ focus: "6", compare: "1", finalView: "runes", runeParticipant: "2" })).toMatchObject({ focus: 6, compare: 1, finalView: "runes", runeParticipant: 2 });
  });
  it("defaults omitted UI state and flags invalid rune participants separately from analysis", () => {
    expect(parseDevelopmentSearch({})).toMatchObject({ finalView: "scoreboard", runeParticipant: undefined, hasInvalidRuneSelection: false });
    expect(parseDevelopmentSearch({ focus: "6", runeParticipant: "99" })).toMatchObject({ focus: 6, runeParticipant: undefined, hasInvalidRuneSelection: true, hasInvalidSelection: false });
  });
  it("retains explicit inspection when returning to the scoreboard", () => {
    expect(developmentHref("NA1_123", 6, 1, undefined, "gold", { finalView: "scoreboard", runeParticipant: 2 })).toContain("runeParticipant=2");
  });
});

describe("match history origin", () => {
  const runId = "00000000-0000-0000-0000-000000000001";
  it("retains a validated history run through complete detail selections", () => {
    const parsed = parseDevelopmentSearch({ historyRunId: runId });
    expect(parsed).toHaveProperty("historyRunId", runId);
    const href = developmentHref("NA1_123", 6, 1, { from: 10, to: 20 }, "xp", { ...parsed, finalView: "runes", runeParticipant: 2 });
    expect(new URL(href, "http://localhost").searchParams.get("historyRunId")).toBe(runId);
  });
  it.each(["https://example.com", "/search?runId=bad", "invalid", [runId, runId]])("does not accept arbitrary or ambiguous origin %s", value => {
    expect(parseDevelopmentSearch({ historyRunId: value }).historyRunId).toBeUndefined();
    if (typeof value === "string") expect(developmentHref("NA1_123", 6, undefined, undefined, undefined, { historyRunId: value })).not.toContain("historyRunId");
  });
});
