import type { PlayerIdentity } from "../lib/player-lookup/regions";
import { isSamplePreview } from "../lib/preview-mode";
import PlayerSearch from "./player-search";

export function PlayerSearchEntry({ initialRunId, initialIdentity }: { initialRunId?: string; initialIdentity?: PlayerIdentity }) {
  if (isSamplePreview()) return <section className="entry-notice" aria-label="Sample preview">
    <strong>Synthetic match data</strong>
    <p>Live player search is unavailable in this sample preview. Explore the synthetic match below.</p>
  </section>;
  return <PlayerSearch initialRunId={initialRunId} initialIdentity={initialIdentity} />;
}
