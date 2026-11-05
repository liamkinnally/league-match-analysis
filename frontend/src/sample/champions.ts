import type { ParticipantRunes } from "../lib/development/types";

// Rune pages use the 16.17.1 Data Dragon catalog.
const pages = {
  fighter: [8000, [8010, 9111, 9104, 8299], 8400, [8444, 8451]],
  grasp: [8400, [8437, 8446, 8444, 8451], 8000, [9111, 9104]],
  engage: [8400, [8439, 8463, 8473, 8242], 8300, [8306, 8347]],
  mage: [8200, [8229, 8226, 8210, 8237], 8300, [8345, 8347]],
  marksman: [8000, [8008, 8009, 9103, 8014], 8200, [8233, 8236]],
  burst: [8100, [8112, 8139, 8137, 8106], 8200, [8226, 8210]],
} as const;
type RunePage = keyof typeof pages;

function champion(id: number, runes: RunePage, ...items: number[]) {
  return { id, runes, items };
}
export const champions = {
  Garen: champion(86, "fighter", 6631, 3006, 3742, 3053, 3071),
  Camille: champion(164, "grasp", 3078, 3111, 3074, 3053, 6333),
  Ornn: champion(516, "grasp", 3068, 3047, 3075, 3065, 3083),
  Darius: champion(122, "fighter", 6631, 3047, 3053, 3742, 3065),
  Renekton: champion(58, "fighter", 6692, 3111, 3071, 3053, 6333),
  Jax: champion(24, "grasp", 3078, 3111, 3748, 3053, 6333),
  Vi: champion(254, "fighter", 3078, 3047, 3071, 3053, 3026),
  LeeSin: champion(64, "fighter", 6692, 3111, 3071, 3053, 6333),
  Sejuani: champion(113, "engage", 3068, 3047, 3075, 3065, 3083),
  Orianna: champion(61, "mage", 6655, 3020, 4645, 3089, 3157),
  Ahri: champion(103, "burst", 3118, 3020, 4645, 3157, 3089),
  Syndra: champion(134, "mage", 6655, 3020, 4645, 3089, 3157),
  Jinx: champion(222, "marksman", 6672, 3006, 3085, 3031, 3036),
  Caitlyn: champion(51, "marksman", 3031, 3006, 3094, 3036, 3072),
  Ashe: champion(22, "marksman", 3153, 3006, 3085, 3031, 3036),
  Nautilus: champion(111, "engage", 3869, 3047, 3190, 3109, 3075, 3110),
  Leona: champion(89, "engage", 3869, 3111, 3190, 3109, 3075, 3110),
  Thresh: champion(412, "engage", 3869, 3158, 3190, 3109, 3065, 3107),
};
export type Champion = keyof typeof champions;

// The same source-counter positions are used by the canonical demo. Unmapped counters stay absent.
const counterRates: Record<number, number[]> = {
  8010: [9], 8437: [19, 13], 8444: [17], 8229: [35], 8237: [9], 8112: [34], 8439: [13, 24],
};

export function runePage(page: RunePage, minutes: number, takedowns: number): ParticipantRunes {
  const [primary, primaryRunes, secondary, secondaryRunes] = pages[page];
  const selections = (ids: readonly number[]) => ids.map(runeId => {
    const rates = counterRates[runeId];
    const value = (rate: number) => Math.round(rate * (minutes * .8 + takedowns * .4));
    return { runeId, metrics: [], counters: rates ? {
      var1: value(rates[0]), var2: rates[1] === undefined ? null : value(rates[1]), var3: null,
    } : null };
  });
  return {
    availability: "available", matchPatch: "16.17", normalizationVersion: "participant-details-v1",
    layoutManifestId: "rune-layout-16.17-v1", performanceStatus: "unverified",
    styles: [
      { styleId: primary, role: "primaryStyle", selections: selections(primaryRunes) },
      { styleId: secondary, role: "subStyle", selections: selections(secondaryRunes) },
    ],
    shards: { offense: page === "mage" || page === "burst" ? 5008 : 5005, flex: 5008, defense: 5001 },
  };
}
