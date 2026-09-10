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
};
