import type { SampleSource } from "../preview/projection";
import type { MatchDevelopmentEvent } from "../lib/development/types";
import { gameEvent } from "./game-events";

// [total price, ...immediate components] for the items used by these 16.17.1 builds.
const shop: Record<number, [number, ...number[]]> = {
  3110: [2500, 3082, 3024], 3082: [1000], 3024: [900],
  3107: [2300, 3108, 3114], 3108: [850], 3114: [600],
  1001: [300], 1006: [300], 1011: [900],
  1018: [600], 1026: [850], 1028: [400],
  1029: [300], 1031: [800], 1033: [400],
  1036: [350], 1037: [875], 1038: [1300],
  1042: [250], 1043: [700], 1053: [900],
  1054: [450], 1055: [450], 1056: [400],
  1058: [1200], 1101: [450], 2019: [1100],
  2021: [1150], 2022: [250], 2055: [75],
  2420: [1600], 3006: [1100, 1001, 1042, 1042], 3020: [1100, 1001],
  3026: [3200, 2019, 1038], 3031: [3500, 1038, 1037, 1018], 3035: [1450],
  3036: [3300, 3035, 6670], 3044: [1100], 3047: [1200, 1001, 1029],
  3051: [1200], 3053: [3200, 1037, 2021, 1028], 3057: [900],
  3065: [2700, 3211, 3067], 3066: [800], 3067: [800],
  3068: [2800, 6660, 1031, 1028], 3071: [3000, 3044, 3067, 1037], 3072: [3400, 1038, 1037, 1053],
  3074: [3300, 3077, 1053, 3133], 3075: [2450, 3076, 1031, 1028], 3076: [800],
  3077: [1200], 3078: [3333, 3057, 3044, 3051], 3083: [3100, 1011, 1011, 3801],
  3085: [2650, 3086, 3144], 3086: [1200], 3089: [3500, 1058, 1058],
  3094: [2650, 3086, 3144], 3109: [2300, 3067, 1031, 1006], 3111: [1250, 1001, 1033],
  3118: [2700, 3802, 1026], 3133: [1050], 3144: [600],
  3145: [1100], 3153: [3200, 1053, 1043, 1037], 3157: [3250, 1058, 2420],
  3158: [900, 1001, 2022], 3190: [2200, 3067, 1029, 1033], 3211: [1250],
  3742: [2900, 3066, 1028, 1031], 3748: [3300, 3077, 2021, 1011], 3801: [800],
  3802: [1200], 3865: [400], 3867: [400],
  3869: [400, 3867], 4645: [3200, 3145, 1058], 6333: [3300, 2019, 1037, 3133],
  6631: [3300, 3077, 3044, 1042], 6655: [2750, 3802, 3145], 6660: [900],
  6670: [1300], 6672: [3000, 6690, 3051, 1043], 6690: [775],
  6692: [2900, 3133, 1037, 1036],
};

export function matchInventory(id: number, build: number[], frames: SampleSource["frames"]) {
  const role = (id - 1) % 5;
  const starter = [1054, 1101, 1056, 1055, 3865][role];
  const items = [role === 4 ? 3869 : starter];
  let spent = shop[starter][0];
  const events: MatchDevelopmentEvent[] = [];
  const itemEvent = (time: number, type: string, itemId: number) => events.push(
    gameEvent(time, type, type === "ITEM_PURCHASED" ? "Item purchased" : "Item removed", id, { participantId: id, itemId }));
  itemEvent(3 + id, "ITEM_PURCHASED", starter);
  if (role === 1) {
    itemEvent(920 + 4 * id, "ITEM_DESTROYED", starter);
    items.length = 0;
  }
  if (role === 4) {
    // Support quest upgrades cost no gold. The base purchase is recorded separately.
    itemEvent(455 + 4 * id, "ITEM_DESTROYED", 3865);
    itemEvent(455 + 4 * id, "ITEM_PURCHASED", 3866);
    itemEvent(787 + 4 * id, "ITEM_DESTROYED", 3866);
    itemEvent(787 + 4 * id, "ITEM_PURCHASED", 3867);
    itemEvent(799 + 4 * id, "ITEM_DESTROYED", 3867);
    itemEvent(799 + 4 * id, "ITEM_PURCHASED", 3869);
  }
  const buy = (itemId: number) => {
    const nextSpend = spent + shop[itemId][0];
    const frame = frames.find(frame => frame.participants[id].totalGold! >= nextSpend + 90);
    // Leave time for the recall and the purchase, and never place it beyond the last observation.
    const at = (frame?.timestampMs ?? Infinity) / 1000 + 8 + id * 1.5;
    if (items.length >= 6 || at >= frames[frames.length - 1].timestampMs / 1000) return false;
    spent = nextSpend;
    items.push(itemId);
    itemEvent(at, "ITEM_PURCHASED", itemId);
    return true;
  };
  for (const item of build.filter(i => i !== 3869)) {
    if (buy(item)) continue;
    for (const part of shop[item].slice(1)) buy(part);
    break;
  }
  return { events, goldSpent: spent, endItemIds: [...items, ...Array(6 - items.length).fill(0),
    role === 1 || role === 4 ? 3364 : 3340] };
}
