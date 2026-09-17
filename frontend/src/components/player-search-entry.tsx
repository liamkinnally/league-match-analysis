import type { PlayerIdentity } from "../lib/player-lookup/regions";
import { isSamplePreview } from "../lib/preview-mode";
import PlayerSearch from "./player-search";
import type { ReactNode } from "react";

export function PlayerSearchEntry({ initialRunId, initialIdentity, children }: { initialRunId?: string; initialIdentity?: PlayerIdentity; children?: ReactNode }) {
  if (isSamplePreview()) return <><section className="entry-notice" aria-label="Sample preview">
    <strong>Synthetic match data</strong>
    <p>Live player search is unavailable in this sample preview. Explore the synthetic match below.</p>
  </section>{children}</>;
  return <PlayerSearch initialRunId={initialRunId} initialIdentity={initialIdentity}>{children}</PlayerSearch>;
}
