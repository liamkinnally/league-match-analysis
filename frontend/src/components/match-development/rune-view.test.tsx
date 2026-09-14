import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { RuneView } from "./rune-view";
import { developmentFixture } from "../../test/match-development-fixture";
import { runeCatalog } from "../../lib/game-assets/runes";
import { assetManifest } from "../../lib/game-assets/manifest";
import type { GameAssetCatalog } from "../../lib/game-assets/types";
import type { MatchDevelopment, ParticipantRunes } from "../../lib/development/types";

const performance = {
  runePerformanceState: { status: "available" as const, patch: "16.17", sourceUrl: "https://raw.communitydragon.org/16.17/plugins/rcp-be-lol-game-data/global/default/v1/perks.json" },
  runePerformance: { "8005": { name: "Press the Attack", metrics: [{ id: "8005:1", label: "Total Damage", variable: 1 as const, unit: "" as const, availability: "available" as const }] } },
};
const assets: GameAssetCatalog = { ...performance, assetVersion: "16.17.1", champions: {}, items: {}, spells: {}, ...runeCatalog("16.17.1"), manifest: assetManifest("16.17.1", "16.17.1", true) };
const runes: ParticipantRunes = {
  availability: "available", matchPatch: "16.17", normalizationVersion: "synthetic-test", layoutManifestId: "rune-layout-16.17-v1", performanceStatus: "available",
  styles: [
    { styleId: 8000, role: "primaryStyle", selections: [8005, 9111, 9104, 8014].map(runeId => ({ runeId, counters: { var1: 0, var2: null, var3: null }, metrics: runeId === 8005 ? [{ id: "synthetic-damage", label: "Synthetic damage", availability: "available", value: 0, unit: "damage", targetScope: "champions", timeScope: "end-of-game", valueBasis: "source-reported", mappingVersion: "test-only" }] : [] })) },
    { styleId: 8400, role: "subStyle", selections: [{ runeId: 8444, metrics: [] }, { runeId: 8451, metrics: [] }] },
  ], shards: { offense: 5008, flex: 5008, defense: 5011 },
};
function dataWithRunes(): MatchDevelopment {
  return { ...developmentFixture, summary: { ...developmentFixture.summary }, roster: developmentFixture.roster.map(person => ({ ...person, gameName: `Synthetic ${person.participantId}`, tagLine: "DEMO", runes: structuredClone(runes) })) };
}
describe("read-only rune view", () => {
  it("keeps source counters separate and exposes unsupported descriptions without a computed total", () => {
    const data = dataWithRunes();
    data.roster[2].runes!.styles[0].selections[0].counters = { var1: 576, var2: 454, var3: 99 };
    const described = { ...assets, runePerformance: { "8005": { name: "Press the Attack", metrics: [
      { id: "first", label: "Total Damage", variable: 1 as const, unit: "" as const, availability: "available" as const },
      { id: "second", label: "Bonus Damage", variable: 2 as const, unit: "" as const, availability: "available" as const },
      { id: "ambiguous", label: "Ambiguous description", unit: "" as const, availability: "unsupported" as const },
    ] } } };
    render(<RuneView data={data} assets={described} onParticipant={vi.fn()} />);
    expect(screen.getByText("576")).toBeVisible();
    expect(screen.getByText("454")).toBeVisible();
    expect(screen.getByText("Unsupported")).toBeVisible();
    expect(screen.queryByText("1,030")).not.toBeInTheDocument();
    expect(screen.queryByText("99")).not.toBeInTheDocument();
  });
  it("shows future-patch counters without pinned layout and marks cached same-patch metadata", () => {
    const data = dataWithRunes();
    data.summary.gameVersion = "99.42.1";
    const snapshot = data.roster[2].runes!;
    snapshot.matchPatch = "99.42";
    snapshot.layoutManifestId = "rune-layout-99.42-v1";
    snapshot.performanceStatus = "unverified";
    snapshot.styles[0].selections[0].counters = { var1: 576, var2: 454, var3: null };
    const futureAssets = { ...assets, assetVersion: "", manifest: undefined, runePerformanceState: { ...performance.runePerformanceState, status: "stale" as const, patch: "99.42", retrievedAt: "2026-09-14T12:00:00Z" } };
    render(<RuneView data={data} assets={futureAssets} onParticipant={vi.fn()} />);
    expect(screen.getByText("576")).toBeVisible();
    expect(screen.getByText(/Using cached rune descriptions for patch 99.42/)).toBeVisible();
    expect(screen.getByText(/Patch-matched rune layout unavailable/)).toBeVisible();
    expect(screen.getByText(/Values available for 1 of 6/)).toBeVisible();
  });
  it("does not render counters using a different patch or negative values", () => {
    const data = dataWithRunes();
    data.roster[2].runes!.styles[0].selections[0].counters = { var1: 987654, var2: null, var3: null };
    const mismatched = { ...assets, runePerformanceState: { ...performance.runePerformanceState, patch: "99.42" } };
    const { rerender } = render(<RuneView data={data} assets={mismatched} onParticipant={vi.fn()} />);
    expect(screen.queryByText("987,654")).not.toBeInTheDocument();
    data.roster[2].runes!.styles[0].selections[0].counters!.var1 = -987654;
    rerender(<RuneView data={data} assets={assets} onParticipant={vi.fn()} />);
    expect(screen.queryByText("-987,654")).not.toBeInTheDocument();
    expect(screen.getByText("Unverified")).toBeVisible();
  });

  it("shows selected and unselected patch-valid rows, three shards and adjacent end-of-game metrics", () => {
    render(<RuneView data={dataWithRunes()} assets={assets} onParticipant={vi.fn()} />);
    expect(screen.getByRole("heading", { name: "Synthetic 6#DEMO · Garen" })).toBeVisible();
    expect(screen.getByRole("img", { name: "Selected Press the Attack" })).toBeVisible();
    expect(screen.getByRole("img", { name: "Not selected Lethal Tempo" })).toBeVisible();
    const secondary = screen.getByRole("region", { name: "Secondary runes" });
    expect(within(secondary).queryByRole("img", { name: /Grasp of the Undying/ })).not.toBeInTheDocument();
    expect(screen.getByText("0")).toBeVisible();
    expect(screen.getByText("End-of-game rune stats")).toBeVisible();
    expect(screen.getByRole("region", { name: "Stat shards" })).toHaveTextContent(/Offense.*Flex.*Defense/);
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
  });
  it("keeps every available portrait as a native participant button and selection independent of focus", () => {
    const onParticipant = vi.fn();
    render(<RuneView data={dataWithRunes()} participantId={1} assets={assets} onParticipant={onParticipant} />);
    const group = screen.getByRole("group", { name: "Inspect participant runes" });
    expect(within(group).getAllByRole("button")).toHaveLength(4);
    expect(within(group).getByRole("button", { name: /Synthetic 1#DEMO.*Darius.*Team 100.*participant 1/ })).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(within(group).getByRole("button", { name: /Synthetic 6#DEMO.*Garen/ }));
    expect(onParticipant).toHaveBeenCalledWith(6);
  });
  it("keeps unknown selections and unavailable values visible when metadata patch mismatches", () => {
    const data = dataWithRunes();
    data.roster[2].runes!.layoutManifestId = null;
    data.roster[2].runes!.styles[0].selections[0] = { runeId: 999999, metrics: [{ id: "unverified", label: "Unverified counter", availability: "unverified", value: 999, unit: "damage", targetScope: "unknown", timeScope: "end-of-game", valueBasis: "source-reported", mappingVersion: "test" }] };
    render(<RuneView data={data} assets={assets} onParticipant={vi.fn()} />);
    expect(screen.getByText("Rune 999999")).toBeVisible();
    expect(screen.getByText(/Patch-matched rune layout unavailable/)).toBeVisible();
    expect(screen.getAllByText("Performance values not available.").length).toBeGreaterThan(0);
    expect(screen.queryByText("999 damage")).not.toBeInTheDocument();
    expect(screen.queryByRole("img", { name: "Not selected Lethal Tempo" })).not.toBeInTheDocument();
  });
  it("falls back to focal participant for an unavailable participant and legacy missing runes", () => {
    render(<RuneView data={developmentFixture} participantId={10} assets={null} onParticipant={vi.fn()} />);
    expect(screen.getByText(/Requested rune participant unavailable/)).toBeVisible();
    expect(screen.getByText("Rune selections not recorded for this participant.")).toBeVisible();
  });
  it("shows all ten participant portraits even with repeated champions and distinct player identities", () => {
    const data = dataWithRunes();
    data.roster = Array.from({ length: 10 }, (_, index) => ({ ...data.roster[2], participantId: index + 1, teamId: index < 5 ? 100 : 200, gameName: `Synthetic ${index + 1}` }));
    render(<RuneView data={data} assets={assets} onParticipant={vi.fn()} />);
    const group = screen.getByRole("group", { name: "Inspect participant runes" });
    expect(within(group).getAllByRole("button")).toHaveLength(10);
    expect(within(group).getByRole("button", { name: /Synthetic 10#DEMO.*participant 10/ })).toBeVisible();
    expect(within(group).getAllByRole("button").filter(button => button.getAttribute("aria-pressed") === "true")).toHaveLength(1);
  });
  it("separates participant match totals from rune contributions and keeps unavailable zero distinct", () => {
    const data = dataWithRunes();
    data.roster[2].participantTotals = { totalDamageDealt: 12000, totalDamageDealtToChampions: null, totalHeal: 0, totalHealsOnTeammates: null, totalDamageShieldedOnTeammates: null };
    data.roster[2].runes!.styles[0].selections[0].counters = { var1: null, var2: null, var3: null };
    render(<RuneView data={data} assets={assets} onParticipant={vi.fn()} />);
    expect(screen.getByText("Total Damage").closest("div")).toHaveTextContent("Not recorded");
    expect(screen.getAllByText("Not recorded").length).toBeGreaterThan(0);
    fireEvent.click(screen.getByText("Match totals context"));
    expect(screen.getByText("Participant-wide end-of-game totals. These values are not attributed to runes.")).toBeVisible();
    expect(screen.getByText("12,000")).toBeVisible();
  });
});
