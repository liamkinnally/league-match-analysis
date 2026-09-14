import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { EventFeed } from "./event-feed";
import { developmentFixture } from "../../test/match-development-fixture";
import type { MatchDevelopmentEvent } from "../../lib/development/types";
import type { GameAssetCatalog } from "../../lib/game-assets/types";

const asset = (name: string) => ({ name, imageUrl: `/test-assets/${name.replaceAll(" ", "-")}.svg` });
const assets: GameAssetCatalog = {
  assetVersion: "16.17.1", champions: {}, spells: {}, items: { "3071": asset("Black Cleaver") },
  events: { WARD_EYE: asset("Ward eye"), WARD_SIGHT: asset("Sight ward"), TOWER_BUILDING: asset("Turret") },
  abilities: { "86": { Q: asset("Decisive Strike") } },
};
const event = (type: string, fields: Record<string, unknown> = {}, changes: Partial<MatchDevelopmentEvent> = {}): MatchDevelopmentEvent => ({
  ...developmentFixture.events[0], type, fields, itemId: null, frameAtMs: 600000, frameEventIndex: 0, ...changes,
});
function feed(events: MatchDevelopmentEvent[], focus = 6, catalog: GameAssetCatalog | null = assets) {
  const result = render(<EventFeed data={{ ...developmentFixture, events, summary: { ...developmentFixture.summary, focusParticipantId: focus } }} interval={{ from: 505210, to: 600000 }} assets={catalog} />);
  fireEvent.click(screen.getByText("Events in interval"));
  return result;
}

describe("event feed", () => {
  it.each([6, 1])("colors the allied ward eye itself blue from focal participant %s", (focus) => {
    feed([event("WARD_PLACED", { wardType: "SIGHT_WARD" }, { actorParticipantId: focus }), event("WARD_PLACED", { wardType: "SIGHT_WARD" }, { actorParticipantId: focus === 6 ? 1 : 6, frameEventIndex: 1 })], focus);
    const rows = screen.getAllByRole("listitem");
    expect(within(rows[0]).getByText("Ally")).toBeVisible();
    expect(within(rows[1]).getByText("Enemy")).toBeVisible();
    expect(rows[0].querySelector("[data-ward-glyph]")).toHaveStyle({ backgroundColor: "#79b5ef" });
    expect(rows[1].querySelector("[data-ward-glyph]")).toHaveStyle({ backgroundColor: "#e29191" });
  });
  it("keeps exact chronology and inclusive endpoints accessible on touch", () => {
    feed([
      event("ITEM_SOLD", { itemId: 3071 }, { timestampMs: 600000, frameEventIndex: 0 }),
      event("WARD_PLACED", { wardType: "SIGHT_WARD" }, { timestampMs: 505999, frameEventIndex: 2 }),
      event("WARD_PLACED", { wardType: "SIGHT_WARD" }, { timestampMs: 505210, frameEventIndex: 1 }),
      event("WARD_PLACED", {}, { timestampMs: 600001, frameEventIndex: 3 }),
    ]);
    const rows = screen.getAllByRole("listitem");
    expect(rows).toHaveLength(3);
    expect(rows.map(row => row.querySelector("time")?.textContent)).toEqual(["8:25.210", "8:25.999", "10:00"]);
    fireEvent.click(within(rows[2]).getByText("Record details"));
    expect(within(rows[2]).getByText("Exact recorded time: 10:00.000")).toBeVisible();
  });
  it("uses champion ability art with slot and rank-up text but no invented rank or cast", () => {
    feed([event("SKILL_LEVEL_UP", { skillSlot: 1 })]);
    expect(screen.getByRole("img", { name: "Decisive Strike" })).toBeVisible();
    expect(screen.getByText("Ability rank-up Q · Decisive Strike")).toBeVisible();
    expect(screen.queryByText(/rank 1|cast/i)).not.toBeInTheDocument();
  });
  it("preserves sold, removed, unknown destroyer and future event meanings with failed images", () => {
    feed([
      event("ITEM_SOLD", { itemId: 3071 }),
      event("ITEM_DESTROYED", { itemId: 3071 }, { frameEventIndex: 1 }),
      event("BUILDING_KILL", { teamId: 100, buildingType: "TOWER_BUILDING", towerType: "OUTER_TURRET" }, { actorParticipantId: 0, frameEventIndex: 2 }),
      event("FUTURE_EVENT", {}, { actorParticipantId: null, frameEventIndex: 3 }),
    ]);
    for (const image of screen.getAllByRole("img")) fireEvent.error(image);
    expect(screen.getByText("Sold Black Cleaver")).toBeVisible();
    expect(screen.getByText("Removed Black Cleaver from inventory")).toBeVisible();
    expect(screen.getByText("Unknown destroyer")).toBeVisible();
    expect(screen.getByText("Structure owner unverified.")).toBeVisible();
    expect(screen.getByText("Future event")).toBeVisible();
  });
  it("keeps ward ownership unknown and does not suggest Oracle Lens detection", () => {
    feed([event("WARD_KILL", { wardType: "SIGHT_WARD" })]);
    expect(screen.getByText("Destroyed Sight ward")).toBeVisible();
    expect(screen.getByText("Ward owner not recorded.")).toBeVisible();
    expect(screen.queryByRole("img", { name: /Oracle/ })).not.toBeInTheDocument();
  });
  it("distinguishes observed empty assists from unavailable assist data", () => {
    feed([
      event("CHAMPION_KILL", {}, { assistersObserved: true }),
      event("CHAMPION_KILL", {}, { assistersObserved: false, frameEventIndex: 1 }),
    ]);
    expect(screen.getByText("No assists recorded.")).toBeVisible();
    expect(screen.getByText("Assists not recorded.")).toBeVisible();
  });
});
