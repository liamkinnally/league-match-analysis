import Link from "next/link";
import { PlayerSearchEntry } from "../components/player-search-entry";
import { isSamplePreview } from "../lib/preview-mode";
import { EntryShell } from "../components/entry-shell";

export const dynamic = "force-dynamic";

export default function Home() {
  const sample = isSamplePreview();
  return <EntryShell active="home" compact>
    <header className="entry-heading">
      <p className="development-kicker">Match history</p>
      <h1>{sample ? "Sample player profile" : "Find a player"}</h1>
      <p>{sample ? "Browse a Challenger player’s match history and open a game to review its timeline." : "Search a player to explore their match history, stats, and timelines."}</p>
    </header>
    <PlayerSearchEntry />
    <Link className="entry-sample" href="/sample/profile" aria-label="Explore sample profile">
      <div><strong>Explore sample profile</strong><span>A fictional Challenger player with eight matches to explore.</span></div>
      <span className="entry-sample__arrow" aria-hidden="true">→</span>
    </Link>
  </EntryShell>;
}
