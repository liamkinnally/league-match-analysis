"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import type { GameAssetCatalog, GameAssetOptions } from "./types";

export function useGameAssetCatalogs(gameVersions: string[], options: GameAssetOptions = {}): Record<string, GameAssetCatalog | null> {
  const versionKey = [...new Set(gameVersions)].sort().join(",");
  const championKey = [...new Set(options.championIds ?? [])].filter((id) => Number.isSafeInteger(id) && id > 0).sort((a,b) => a-b).slice(0, 10).join(",");
  const includeRunes = options.includeRunes === true;
  const optionKey = `${championKey}|${includeRunes}`;
  const [catalogs, setCatalogs] = useState<{ key: string; values: Record<string, GameAssetCatalog | null> }>({ key: optionKey, values: {} });
  const cacheRef = useRef({ key: optionKey, values: {} as Record<string, GameAssetCatalog | null>, nextCheck: {} as Record<string, number> });

  useEffect(() => {
    if (!versionKey) return;
    const controller = new AbortController();
    const versions = versionKey.split(",");
    const cache: typeof cacheRef.current = cacheRef.current.key === optionKey ? cacheRef.current : { key: optionKey, values: {}, nextCheck: {} };
    cacheRef.current = cache;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const retryDelay = 60_000, successfulTtl = 86_400_000;
    const stale = (catalog: GameAssetCatalog | null | undefined): GameAssetCatalog | null => {
      if (!catalog) return null;
      if (!catalog.runePerformanceState || catalog.runePerformanceState.status === "unavailable") return catalog;
      return { ...catalog, runePerformanceState: { ...catalog.runePerformanceState, status: "stale", retryAt: new Date(Date.now() + retryDelay).toISOString(), reason: "The last successful metadata is shown while the catalog request is unavailable." } };
    };
    const nextCheck = (catalog: GameAssetCatalog | null | undefined) => {
      const now = Date.now(), state = catalog?.runePerformanceState;
      if (!includeRunes) return catalog ? Infinity : 0;
      if (!catalog || catalog.manifest?.fallbackReason || state?.status === "unavailable" || state?.status === "stale")
        return now + Math.max(retryDelay, Date.parse(state?.retryAt ?? "") - now || retryDelay);
      return state?.retrievedAt ? Math.max(now + retryDelay, Date.parse(state.retrievedAt) + successfulTtl) : Infinity;
    };
    const readBatch = async (batch: string[]) => {
      const request = new AbortController(), abort = () => request.abort();
      if (controller.signal.aborted) return;
      controller.signal.addEventListener("abort", abort, { once: true });
      const timeout = setTimeout(abort, 12_000);
      const query = new URLSearchParams({ versions: batch.join(",") });
      if (championKey) query.set("champions", championKey);
      if (includeRunes) query.set("runes", "1");
      const publish = (next: Record<string, GameAssetCatalog | null>) => {
        if (controller.signal.aborted || cacheRef.current !== cache) return;
        const values = { ...cache.values };
        for (const version of batch) {
          values[version] = next[version] ?? (includeRunes ? stale(values[version]) : null);
          cache.nextCheck[version] = nextCheck(next[version]);
        }
        cache.values = values;
        setCatalogs({ key: optionKey, values });
      };
      try {
        const response = await fetch(`/api/game-assets?${query}`, { signal: request.signal, ...(includeRunes ? { cache: "no-store" as const } : {}) });
        if (!response.ok) throw new Error("ASSET_REQUEST_FAILED");
        const body: unknown = await response.json();
        if (request.signal.aborted || !body || typeof body !== "object" || Array.isArray(body)) throw new Error("ASSET_REQUEST_FAILED");
        publish(body as Record<string, GameAssetCatalog | null>);
      } catch {
        publish({});
      } finally { clearTimeout(timeout); controller.signal.removeEventListener("abort", abort); }
    };
    const read = async () => {
      const missing = versions.filter(version => cache.nextCheck[version] === undefined || cache.nextCheck[version] <= Date.now());
      const batches: string[][] = [];
      for (let index = 0; index < missing.length; index += 5) batches.push(missing.slice(index, index + 5));
      let cursor = 0;
      const worker = async () => {
        while (!controller.signal.aborted && cursor < batches.length) await readBatch(batches[cursor++]);
      };
      await Promise.all(Array.from({ length: Math.min(2, batches.length) }, worker));
      const due = Math.min(...versions.map(version => cache.nextCheck[version] ?? Infinity));
      if (includeRunes && !controller.signal.aborted && Number.isFinite(due))
        timer = setTimeout(() => { void read(); }, Math.max(retryDelay, Math.min(successfulTtl, due - Date.now())));
    };
    void read();
    return () => { controller.abort(); if (timer) clearTimeout(timer); };
  }, [versionKey, championKey, includeRunes, optionKey]);

  return useMemo(() => {
    const requested = new Set(versionKey.split(","));
    return catalogs.key === optionKey ? Object.fromEntries(Object.entries(catalogs.values).filter(([version]) => requested.has(version))) : {};
  }, [catalogs, versionKey, optionKey]);
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
