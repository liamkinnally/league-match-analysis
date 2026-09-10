"use client";

import { useEffect, useState } from "react";
import type { GameAssetCatalog } from "./types";

export function useGameAssetCatalogs(gameVersions: string[]): Record<string, GameAssetCatalog | null> {
  const versionKey = [...new Set(gameVersions)].sort().slice(0, 5).join(",");
  const [catalogs, setCatalogs] = useState<Record<string, GameAssetCatalog | null>>({});

  useEffect(() => {
    if (!versionKey) return;
    const controller = new AbortController();
    void fetch(`/api/game-assets?versions=${encodeURIComponent(versionKey)}`, {
      signal: controller.signal,
    })
      .then((response) => response.ok ? response.json() : {})
      .then((body: unknown) => {
        if (!controller.signal.aborted && body && typeof body === "object") {
          setCatalogs(body as Record<string, GameAssetCatalog | null>);
        }
      })
      .catch(() => undefined);
    return () => controller.abort();
  }, [versionKey]);

  return catalogs;
}
