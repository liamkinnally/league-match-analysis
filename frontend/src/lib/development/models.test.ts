import { describe, expect, it } from "vitest";
import { chartRows, intervalChangeLabel, timeLabel } from "./chart-model";
import {
  finalItemSlotOrder,
  objectiveColumns,
  currentRankAverage,
  rankDisplay,
} from "./results";
import { dragonAcquisitions, eventDisplayRows } from "./events";
import { developmentFixture } from "../../test/match-development-fixture";
import { developmentHref, parseDevelopmentSearch } from "./route";

describe("reviewed data models", () => {
  it.each([
    ["AIR_DRAGON", "Cloud Dragon", "dragon_cloud"],
    ["FIRE_DRAGON", "Infernal Dragon", "dragon_infernal"],
    ["EARTH_DRAGON", "Mountain Dragon", "dragon_mountain"],
    ["WATER_DRAGON", "Ocean Dragon", "dragon_ocean"],
    ["HEXTECH_DRAGON", "Hextech Dragon", "dragon_hextech"],
    ["CHEMTECH_DRAGON", "Chemtech Dragon", "dragon_chemtech"],
    ["ELDER_DRAGON", "Elder Dragon", "dragon_elder"],
  ])("resolves %s to its published dragon icon", (type, name, file) => {
    const event = {
      ...developmentFixture.events[2],
      type: "ELITE_MONSTER_KILL",
      fields: { monsterType: "DRAGON", monsterSubType: type },
    };
    expect(dragonAcquisitions([event], developmentFixture.roster, "16.17.810.4348"))
      .toEqual([expect.objectContaining({
        name,
        imageUrl: `https://raw.communitydragon.org/16.17/game/assets/ux/minimap/icons/${file}.png`,
        timestampMs: event.timestampMs,
        teamId: 200,
      })]);
  });
  it("keeps exact samples, missing gaps and comparison direction", () => {
    const rows = chartRows(developmentFixture.samples, "gold", true);
    expect(rows.map((row) => row.timestampMs)).toEqual([
      480000, 540000, 600000,
    ]);
    expect(rows.map((row) => row.value)).toEqual([100, null, 510]);
    expect(rows[0].comparison).toBe(3000);
    expect(chartRows(developmentFixture.samples, "gold", false)[1].value).toBe(
      3700,
    );
    expect(timeLabel(120491, true)).toBe("2:00.491");
    expect(intervalChangeLabel(-80, -20)).toBe("Gap narrowed");
    expect(intervalChangeLabel(40, 20)).toBe("Lead narrowed");
    expect(intervalChangeLabel(null, 0)).toBe("Change unavailable");
  });
  it("compacts occupied regular items stably, keeping trinket fixed", () => {
    const inventory = Object.freeze([0, 3157, 3100, 1082, 4645, 6655, 3340]);
    expect(finalItemSlotOrder(inventory)).toEqual([1, 2, 3, 4, 5, 0, 6]);
    expect(inventory[0]).toBe(0);
    expect(finalItemSlotOrder([0, 0, 0, 0, 0, 0, 3340])).toEqual([
      0, 1, 2, 3, 4, 5, 6,
    ]);
  });
  it("uses supported objectives for the match patch and map", () => {
    expect(objectiveColumns("16.17.810.4348", 11).map((c) => c.key)).toEqual([
      "tower",
      "inhibitor",
      "dragon",
      "baron",
      "riftHerald",
      "horde",
    ]);
    expect(objectiveColumns("15.1.1", 11).map((c) => c.key)).toContain(
      "atakhan",
    );
    expect(objectiveColumns("13.24.1", 11).map((c) => c.key)).not.toContain(
      "horde",
    );
    expect(objectiveColumns("16.17.1", 12).map((c) => c.key)).not.toContain(
      "dragon",
    );
  });
  it("averages only verified current ranks in the selected queue", () => {
    const players = [
      {
        participantId: 1,
        status: "ranked" as const,
        tier: "GOLD",
        division: "III",
      },
      {
        participantId: 2,
        status: "ranked" as const,
        tier: "GOLD",
        division: "II",
      },
      { participantId: 3, status: "unranked" as const },
      { participantId: 4, status: "unavailable" as const },
    ];
    const avg = currentRankAverage(players, 4);
    expect(avg.label).toBe("Gold II");
    expect(avg.contributors).toBe(2);
    expect(currentRankAverage([], 10).label).toBeNull();
    expect(rankDisplay(players[2]).label).toBe("Unranked");
    expect(rankDisplay({ participantId: 1, status: "loading" }).label).toBe(
      "Loading…",
    );
    expect(
      rankDisplay({
        participantId: 1,
        status: "ranked",
        tier: "GOLD",
        division: "INVALID",
      }).label,
    ).toBe("Unavailable");
  });
  it("retains metric and precise interval URL state", () => {
    expect(
      developmentHref("NA1_123", 1, 6, { from: 120491, to: 240503 }, "xp"),
    ).toContain("from=120491&to=240503&metric=xp");
    expect(
      parseDevelopmentSearch({ focus: "1", compare: "6", metric: "cs" }).metric,
    ).toBe("cs");
    expect(parseDevelopmentSearch({ metric: "bad" }).metric).toBe("gold");
  });
  it("pairs exact Control Ward placement records while preserving sale/removal meaning and all sources", () => {
    const base = {
      ...developmentFixture.events[0],
      frameAtMs: 180000,
      itemId: 2055,
      participantIds: [6],
      actorParticipantId: 6,
    };
    const removal = {
      ...base,
      timestampMs: 121234,
      frameEventIndex: 1,
      type: "ITEM_DESTROYED",
      fields: { itemId: 2055 },
    };
    const placement = {
      ...base,
      timestampMs: 121234,
      frameEventIndex: 2,
      type: "WARD_PLACED",
      fields: { wardType: "CONTROL_WARD" },
    };
    const sold = {
      ...base,
      timestampMs: 122234,
      frameEventIndex: 3,
      type: "ITEM_SOLD",
      fields: { itemId: 2055 },
    };
    const events = [removal, placement, sold];
    const rows = eventDisplayRows(events, developmentFixture.roster, null);
    expect(rows).toHaveLength(2);
    expect(rows[0].action).toBe("Placed");
    expect(rows[1].action).toBe("Sold");
    expect(rows.flatMap((row) => row.records)).toEqual(events);
    const ambiguous = eventDisplayRows(
      [removal, { ...placement, actorParticipantId: 7 }],
      developmentFixture.roster,
      null,
    );
    expect(ambiguous).toHaveLength(2);
    expect(ambiguous[0].action).toBe("Removed");
  });
  it("resolves multikill victims from matching kills without adding kills or guessing unknown ward types", () => {
    const base = {
      ...developmentFixture.events[1],
      frameAtMs: 600000,
      actorParticipantId: 6,
    };
    const events = [
      {
        ...base,
        timestampMs: 590000,
        frameEventIndex: 1,
        type: "CHAMPION_KILL",
        fields: {},
        targetParticipantId: 1,
      },
      {
        ...base,
        timestampMs: 599000,
        frameEventIndex: 2,
        type: "CHAMPION_KILL",
        fields: {},
        targetParticipantId: 2,
      },
      {
        ...base,
        timestampMs: 599000,
        frameEventIndex: 3,
        type: "CHAMPION_SPECIAL_KILL",
        fields: { killType: "KILL_MULTI", multiKillLength: 2 },
        targetParticipantId: null,
      },
    ];
    const rows = eventDisplayRows(events, developmentFixture.roster, null);
    expect(rows[2].victims.map((p) => p.participantId)).toEqual([1, 2]);
    expect(rows[2].records).toHaveLength(1);
    expect(
      eventDisplayRows(events.slice(1), developmentFixture.roster, null).at(-1)
        ?.victims,
    ).toEqual([]);
  });
});
