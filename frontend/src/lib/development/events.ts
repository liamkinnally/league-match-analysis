import type { GameAsset, GameAssetCatalog } from "../game-assets/types";
import type {
  MatchDevelopmentEvent,
  MatchDevelopmentParticipant,
} from "./types";
import { participantName } from "./results";

const WARDS: Record<string, string> = {
  SIGHT_WARD: "Sight ward",
  YELLOW_TRINKET: "Yellow trinket ward",
  CONTROL_WARD: "Control Ward",
  BLUE_TRINKET: "Farsight ward",
};
const DRAGONS: Record<string, { name: string; file: string }> = {
  AIR_DRAGON: { name: "Cloud Dragon", file: "dragon_air" },
  FIRE_DRAGON: { name: "Infernal Dragon", file: "dragon_fire" },
  EARTH_DRAGON: { name: "Mountain Dragon", file: "dragon_mountain" },
  WATER_DRAGON: { name: "Ocean Dragon", file: "dragon_ocean" },
  HEXTECH_DRAGON: { name: "Hextech Dragon", file: "dragon_hextech" },
  CHEMTECH_DRAGON: { name: "Chemtech Dragon", file: "dragon_chemtech" },
  ELDER_DRAGON: { name: "Elder Dragon", file: "dragon_elder" },
};
const MONSTERS: Record<string, string> = {
  BARON_NASHOR: "Baron Nashor",
  RIFTHERALD: "Rift Herald",
  HORDE: "Void grub",
  ATAKHAN: "Atakhan",
};
const LANES: Record<string, string> = {
  TOP_LANE: "Top lane",
  MID_LANE: "Middle lane",
  BOT_LANE: "Bottom lane",
};
export const eventKey = (event: MatchDevelopmentEvent) =>
  `${event.timestampMs}:${event.frameAtMs ?? "unknown"}:${event.frameEventIndex ?? "unknown"}:${event.type ?? event.label}`;

function eventType(event: MatchDevelopmentEvent): string {
  if (event.type) return event.type;
  // Compatibility with older stored/test development responses, before typed fields existed.
  if (event.label === "Champion kill") return "CHAMPION_KILL";
  if (event.label.startsWith("Purchased item")) return "ITEM_PURCHASED";
  if (event.label.startsWith("Removed item")) return "ITEM_DESTROYED";
  if (event.label.startsWith("Sold item")) return "ITEM_SOLD";
  return "UNKNOWN";
}
export type DisplayEvent = {
  id: string;
  type: string;
  timestampMs: number;
  identity: MatchDevelopmentParticipant | undefined;
  identityText: string;
  action: string;
  subject: string | null;
  itemId: number | null;
  item: GameAsset | undefined;
  victims: MatchDevelopmentParticipant[];
  assists: MatchDevelopmentParticipant[];
  note?: string;
  records: MatchDevelopmentEvent[];
  relatedKillRecords: MatchDevelopmentEvent[];
};

export function eventDisplayRows(
  events: MatchDevelopmentEvent[],
  roster: MatchDevelopmentParticipant[],
  assets: GameAssetCatalog | null,
): DisplayEvent[] {
  const people = new Map(
    roster.map((person) => [person.participantId, person]),
  );
  const itemName = (id: number | null) =>
    id === null
      ? "Item unavailable"
      : (assets?.items[String(id)]?.name ?? "Unresolved item");
  const rows: DisplayEvent[] = events.map((event, index) => {
    const type = eventType(event),
      fields = event.fields ?? {},
      identity = people.get(event.actorParticipantId ?? -1),
      target = people.get(event.targetParticipantId ?? -1);
    let itemId =
      typeof fields.itemId === "number" ? fields.itemId : event.itemId;
    let action = "Recorded event",
      subject: string | null = null,
      note: string | undefined;
    let victims = type === "CHAMPION_KILL" && target ? [target] : [];
    let relatedKillRecords: MatchDevelopmentEvent[] = [];
    if (type === "CHAMPION_KILL") {
      action = "Killed";
      subject = target
        ? participantName(target, assets)
        : "Champion unavailable";
    } else if (type === "CHAMPION_SPECIAL_KILL") {
      const count = fields.multiKillLength;
      action =
        fields.killType === "KILL_MULTI"
          ? ({
              2: "Double kill",
              3: "Triple kill",
              4: "Quadra kill",
              5: "Pentakill",
            }[Number(count)] ?? "Multikill")
          : fields.killType === "KILL_FIRST_BLOOD"
            ? "First blood"
            : "Special kill";
      if (
        fields.killType === "KILL_MULTI" &&
        Number.isInteger(count) &&
        Number(count) >= 2 &&
        Number(count) <= 5
      ) {
        const kills = events
          .slice(0, index)
          .filter(
            (record) =>
              eventType(record) === "CHAMPION_KILL" &&
              record.actorParticipantId === event.actorParticipantId,
          )
          .slice(-Number(count));
        if (
          kills.length === count &&
          kills.at(-1)?.timestampMs === event.timestampMs &&
          kills.every(
            (record) =>
              people.has(record.targetParticipantId ?? -1) &&
              record.timestampMs <= event.timestampMs,
          )
        ) {
          relatedKillRecords = kills;
          victims = kills.map(
            (record) => people.get(record.targetParticipantId!)!,
          );
        }
      }
    } else if (type === "WARD_PLACED" || type === "WARD_KILL") {
      action = type === "WARD_PLACED" ? "Placed" : "Destroyed";
      subject = WARDS[String(fields.wardType)] ?? "Ward (type unavailable)";
      itemId =
        (
          {
            CONTROL_WARD: 2055,
            YELLOW_TRINKET: 3340,
            BLUE_TRINKET: 3363,
          } as Record<string, number>
        )[String(fields.wardType)] ?? null;
    } else if (
      ["ITEM_PURCHASED", "ITEM_DESTROYED", "ITEM_SOLD"].includes(type)
    ) {
      action =
        type === "ITEM_PURCHASED"
          ? "Purchased"
          : type === "ITEM_SOLD"
            ? "Sold"
            : "Removed";
      subject = itemName(itemId);
      if (type === "ITEM_DESTROYED")
        note =
          itemId === 2055
            ? "No matching ward placement recorded."
            : "Reason not recorded.";
    } else if (type === "ITEM_UNDO") {
      const before =
        typeof fields.beforeId === "number" && fields.beforeId > 0
          ? fields.beforeId
          : null;
      const after =
        typeof fields.afterId === "number" && fields.afterId > 0
          ? fields.afterId
          : null;
      itemId = before ?? after;
      if (before && fields.afterId === 0) {
        action = "Undid purchase";
        subject = itemName(before);
      } else if (fields.beforeId === 0 && after) {
        action = "Undid sale";
        subject = itemName(after);
      } else {
        action = "Undid item change";
        subject = `${before ? itemName(before) : fields.beforeId === 0 ? "No item" : "Before item unavailable"} → ${after ? itemName(after) : fields.afterId === 0 ? "No item" : "After item unavailable"}`;
      }
    } else if (type === "LEVEL_UP") {
      action = "Reached";
      subject = Number.isInteger(fields.level)
        ? `level ${fields.level}`
        : "Level unavailable";
    } else if (type === "SKILL_LEVEL_UP") {
      action = "Upgraded";
      subject =
        { 1: "Q", 2: "W", 3: "E", 4: "R" }[Number(fields.skillSlot)] ??
        "Ability unavailable";
    } else if (type === "ELITE_MONSTER_KILL") {
      action = "Secured";
      subject =
        fields.monsterType === "DRAGON"
          ? (DRAGONS[String(fields.monsterSubType)]?.name ??
            "Dragon (type unavailable)")
          : (MONSTERS[String(fields.monsterType)] ??
            "Monster (type unavailable)");
    } else if (type === "TURRET_PLATE_DESTROYED") {
      action = "Turret plate destroyed";
      subject = LANES[String(fields.laneType)] ?? "Lane unavailable";
    } else if (type === "BUILDING_KILL") {
      action = "Destroyed";
      subject =
        ({
          TOWER_BUILDING: "Tower",
          INHIBITOR_BUILDING: "Inhibitor",
          NEXUS_BUILDING: "Nexus",
        }[String(fields.buildingType)] ?? "Structure") +
        (LANES[String(fields.laneType)]
          ? ` — ${LANES[String(fields.laneType)]}`
          : "");
    } else if (type === "GAME_END") action = "Match ended";
    else if (type === "PAUSE_END") action = "Play resumed";
    else if (type === "DRAGON_SOUL_GIVEN") {
      action = "Dragon soul awarded";
      subject = typeof fields.name === "string" ? fields.name : null;
    } else if (type === "UNKNOWN" && event.label !== "Match event")
      action = event.label;
    else if (type !== "UNKNOWN")
      action = type
        .toLowerCase()
        .replaceAll("_", " ")
        .replace(/^./, (character) => character.toUpperCase());
    return {
      id: `${eventKey(event)}:${index}`,
      type,
      timestampMs: event.timestampMs,
      identity,
      identityText: identity
        ? participantName(identity, assets)
        : event.actorParticipantId === 0
          ? "System"
          : "Actor unavailable",
      action,
      subject,
      itemId,
      item: itemId ? assets?.items[String(itemId)] : undefined,
      victims,
      assists: event.assistersObserved
        ? event.assisterParticipantIds
            .map((id) => people.get(id))
            .filter((person): person is MatchDevelopmentParticipant => !!person)
        : [],
      note,
      records: [event],
      relatedKillRecords,
    };
  });
  const candidates = new Map<
    string,
    { removals: number[]; placements: number[] }
  >();
  rows.forEach((row, index) => {
    const event = row.records[0];
    if (
      !Number.isInteger(event.actorParticipantId) ||
      (event.actorParticipantId ?? 0) <= 0 ||
      !Number.isInteger(event.frameAtMs) ||
      !Number.isInteger(event.frameEventIndex)
    )
      return;
    const removal = row.type === "ITEM_DESTROYED" && row.itemId === 2055,
      placement =
        row.type === "WARD_PLACED" && event.fields?.wardType === "CONTROL_WARD";
    if (!removal && !placement) return;
    const key = `${event.actorParticipantId}:${event.timestampMs}:${event.frameAtMs}`,
      group = candidates.get(key) ?? { removals: [], placements: [] };
    group[removal ? "removals" : "placements"].push(index);
    candidates.set(key, group);
  });
  const paired = new Map<number, DisplayEvent>(),
    supporting = new Set<number>();
  for (const { removals, placements } of candidates.values()) {
    if (removals.length !== 1 || placements.length !== 1) continue;
    const a = removals[0],
      b = placements[0];
    if (
      Math.abs(a - b) !== 1 ||
      Math.abs(events[a].frameEventIndex! - events[b].frameEventIndex!) !== 1
    )
      continue;
    const first = Math.min(a, b),
      last = Math.max(a, b);
    paired.set(first, { ...rows[b], records: [events[first], events[last]] });
    supporting.add(last);
  }
  return rows.flatMap((row, index) =>
    supporting.has(index) ? [] : [paired.get(index) ?? row],
  );
}

export function dragonAcquisitions(
  events: MatchDevelopmentEvent[],
  roster: MatchDevelopmentParticipant[],
  gameVersion: string,
) {
  const patch = gameVersion.split(".").slice(0, 2).join(".");
  return events
    .filter(
      (event) =>
        event.type === "ELITE_MONSTER_KILL" &&
        event.fields?.monsterType === "DRAGON",
    )
    .toSorted(
      (a, b) =>
        a.timestampMs - b.timestampMs ||
        (a.frameAtMs ?? 0) - (b.frameAtMs ?? 0) ||
        (a.frameEventIndex ?? 0) - (b.frameEventIndex ?? 0),
    )
    .map((event) => {
      const dragon = DRAGONS[String(event.fields?.monsterSubType)];
      const recordedTeam = event.fields?.killerTeamId;
      const teamId =
        typeof recordedTeam === "number"
          ? recordedTeam
          : (roster.find(
              (person) => person.participantId === event.actorParticipantId,
            )?.teamId ?? null);
      return {
        id: eventKey(event),
        timestampMs: event.timestampMs,
        teamId,
        name: dragon?.name ?? "Dragon type unavailable",
        imageUrl:
          dragon && /^\d+\.\d+$/.test(patch)
            ? `https://raw.communitydragon.org/${patch}/game/assets/ux/minimap/icons/${dragon.file}.png`
            : null,
      };
    });
}
