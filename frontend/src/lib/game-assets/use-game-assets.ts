"use client";

import { useEffect, useState } from "react";
import type { GameAssetCatalog, GameAssetOptions } from "./types";

export function useGameAssetCatalogs(gameVersions: string[], options: GameAssetOptions = {}): Record<string, GameAssetCatalog | null> {
  const versionKey = [...new Set(gameVersions)].sort().slice(0, 5).join(",");
  const championKey = [...new Set(options.championIds ?? [])].filter((id) => Number.isSafeInteger(id) && id > 0).sort((a,b) => a-b).slice(0, 10).join(",");
  const includeRunes = options.includeRunes === true;
  const [catalogs, setCatalogs] = useState<Record<string, GameAssetCatalog | null>>({});

  useEffect(() => {
    if (!versionKey) return;
    const controller = new AbortController();
    const query = new URLSearchParams({ versions: versionKey });
    if (championKey) query.set("champions", championKey);
    if (includeRunes) query.set("runes", "1");
    let timer: ReturnType<typeof setTimeout> | undefined;
    const retryDelay = 60_000, successfulTtl = 86_400_000;
    const stale = (catalog: GameAssetCatalog | null | undefined): GameAssetCatalog | null => {
      if (!catalog) return null;
      if (!catalog.runePerformanceState || catalog.runePerformanceState.status === "unavailable") return catalog;
      return { ...catalog, runePerformanceState: { ...catalog.runePerformanceState, status: "stale", retryAt: new Date(Date.now() + retryDelay).toISOString(), reason: "The last successful metadata is shown while the catalog request is unavailable." } };
    };
    const schedule = (delay: number) => { if (includeRunes && !controller.signal.aborted) timer = setTimeout(() => { void read(); }, Math.max(retryDelay, Math.min(successfulTtl, delay))); };
    const read = async () => {
      const request = new AbortController(), abort = () => request.abort();
      controller.signal.addEventListener("abort", abort, { once: true });
      if (controller.signal.aborted) return;
      const timeout = setTimeout(abort, 12_000);
      try {
        const response = await fetch(`/api/game-assets?${query}`, { signal: request.signal, ...(includeRunes ? { cache: "no-store" as const } : {}) });
        if (!response.ok) throw new Error("ASSET_REQUEST_FAILED");
        const body: unknown = await response.json();
        if (controller.signal.aborted || !body || typeof body !== "object" || Array.isArray(body)) return;
        const next = body as Record<string, GameAssetCatalog | null>;
        setCatalogs(previous => Object.fromEntries(versionKey.split(",").map(version => [version, next[version] ?? (includeRunes ? stale(previous[version]) : null)])));
        if (includeRunes) {
          const delays = versionKey.split(",").flatMap(version => {
            const catalog = next[version], state = catalog?.runePerformanceState;
            if (!catalog) return [retryDelay];
            if (catalog.manifest?.fallbackReason || state?.status === "unavailable" || state?.status === "stale") return [Math.max(retryDelay, Date.parse(state?.retryAt ?? "") - Date.now() || retryDelay)];
            if (state?.retrievedAt) return [Date.parse(state.retrievedAt) + successfulTtl - Date.now()];
            return [];
          }).filter(Number.isFinite);
          if (delays.length) schedule(Math.min(...delays));
        }
      } catch {
        if (!controller.signal.aborted && includeRunes) {
          setCatalogs(previous => Object.fromEntries(versionKey.split(",").map(version => [version, stale(previous[version])])));
          schedule(retryDelay);
        }
      } finally { clearTimeout(timeout); controller.signal.removeEventListener("abort", abort); }
    };
    void read();
    return () => { controller.abort(); if (timer) clearTimeout(timer); };
  }, [versionKey, championKey, includeRunes]);

  return catalogs;
}

export function useCurrentProfileAssets(): GameAssetCatalog | null {
  const [catalog, setCatalog] = useState<GameAssetCatalog | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/game-assets?scope=current-profile", { signal: controller.signal })
      .then((response) => response.ok ? response.json() : null)
      .then((body: unknown) => { if (!controller.signal.aborted && body && typeof body === "object" && "assetVersion" in body) setCatalog(body as GameAssetCatalog); })
      .catch(() => undefined);
    return () => controller.abort();
  }, []);
  return catalog;
}
