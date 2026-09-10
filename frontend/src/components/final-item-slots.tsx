import { GameAssetIcon } from "./game-asset-icon";
import { AssetTooltip } from "./asset-tooltip";
import { finalItemSlotOrder } from "../lib/development/results";
import type { GameAssetCatalog } from "../lib/game-assets/types";

export function FinalItemSlots({
  itemIds,
  assets,
  className = "",
  interactive = false,
}: {
  itemIds: number[];
  assets: GameAssetCatalog | null;
  className?: string;
  interactive?: boolean;
}) {
  return (
    <span
      className={`development-items ${className}`.trim()}
      aria-label="Final items"
    >
      {finalItemSlotOrder(itemIds).map((originalIndex, displayIndex) => {
        const itemId = itemIds[originalIndex] ?? 0,
          trinket = originalIndex === 6,
          empty = itemId === 0;
        const label = trinket
          ? "Empty trinket slot"
          : `Empty item slot ${displayIndex + 1}`;
        return (
          <span
            key={originalIndex}
            data-original-slot={originalIndex}
            data-item-id={itemId || undefined}
            className={`${empty ? "development-item-slot--empty " : ""}${trinket ? "development-item-slot--trinket" : ""}`.trim()}
            title={
              empty
                ? label
                : interactive
                  ? undefined
                  : (assets?.items[String(itemId)]?.name ?? `Item ${itemId}`)
            }
            aria-label={empty ? label : undefined}
          >
            {empty ? null : !interactive ? (
              <GameAssetIcon
                asset={assets?.items[String(itemId)]}
                fallback="?"
                className="development-item-icon"
              />
            ) : (
              <AssetTooltip
                asset={assets?.items[String(itemId)]}
                kind="item"
                patch={assets?.assetVersion}
                fallback="?"
                iconClass="development-item-icon"
              />
            )}
          </span>
        );
      })}
    </span>
  );
}
