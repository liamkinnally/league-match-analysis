"use client";

/* eslint-disable @next/next/no-img-element */
import { useState } from "react";
import type { GameAsset } from "../lib/game-assets/types";

export function GameAssetIcon({ asset, fallback, className }: {
  asset?: GameAsset;
  fallback: string;
  className: string;
}) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  if (!asset || failedUrl === asset.imageUrl) {
    return <span className={className} role="img" aria-label={asset?.name ?? fallback}>{fallback}</span>;
  }
  return <img width={32} height={32} className={className} src={asset.imageUrl} alt={asset.name} onError={() => setFailedUrl(asset.imageUrl)} />;
}
