export type GameAsset = {
  name: string;
  imageUrl: string;
  description?: string;
  cooldown?: number[];
  gold?: {
    base: number | null;
    total: number | null;
    sell: number | null;
    purchasable: boolean | null;
  };
};

export type GameAssetCatalog = {
  assetVersion: string;
  champions: Record<string, GameAsset>;
  items: Record<string, GameAsset>;
  spells: Record<string, GameAsset>;
  manifest?: AssetManifest;
  runeTrees?: Record<string, RuneTreeAsset>;
  runePerformance?: Record<string, RunePerformance>;
  runePerformanceState?: RunePerformanceState;
  runes?: Record<string, RuneAsset>;
  statShards?: Record<string, GameAsset & { id: number }>;
  statShardSlots?: number[][];
  abilities?: Record<string, Partial<Record<AbilitySlot, GameAsset>>>;
  events?: Record<string, GameAsset>;
  profileIcons?: Record<string, GameAsset>;
  ranks?: Record<string, GameAsset>;
};

export type RuneTreeAsset = GameAsset & { id: number; slots: number[][] };
export type RuneAsset = GameAsset & { id: number; treeId: number | null; slot: number | null };
export type AbilitySlot = "Q" | "W" | "E" | "R";
export type AssetManifest = {
  id: string;
  scope: "match-patch" | "current-profile";
  gameVersion: string | null;
  dataDragonVersion: string;
  communityDragonVersion: string | null;
  resolverVersion: string;
  semanticDatasets: { sourceUrl: string; sha256: string; projectionSha256?: string; retrievedAt: string }[];
  fallbackReason: string | null;
};
export type GameAssetOptions = { championIds?: number[]; includeRunes?: boolean; currentProfile?: boolean };

export type RunePerformanceMetric = { id: string; label: string; variable?: 1 | 2 | 3; unit: "" | "seconds" | "percent"; availability: "available" | "unsupported"; reason?: string };
export type RunePerformance = { name: string; metrics: RunePerformanceMetric[] };
export type RunePerformanceState = { status: "available" | "stale" | "unavailable"; patch: string; sourceUrl: string; sha256?: string; retrievedAt?: string; retryAt?: string; reason?: string };
