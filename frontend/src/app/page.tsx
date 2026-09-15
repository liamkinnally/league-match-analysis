import { Suspense } from "react";
import { PlayerSearchEntry } from "../components/player-search-entry";
import { isSamplePreview } from "../lib/preview-mode";
import { EntryShell } from "../components/entry-shell";
import { SampleMatchLink, SampleMatchPending } from "../components/sample-match-link";

export const dynamic = "force-dynamic";

export default function Home() {
  const sample = isSamplePreview();
  return <EntryShell active="home" compact>
    <header className="entry-heading">
      <p className="development-kicker">Match history</p>
      <h1>{sample ? "Explore a sample match" : "Find a player"}</h1>
      <p>{sample ? "Compare opponents and review a match using invented data." : "Search a player to explore their match history, stats, and timelines."}</p>
    </header>
    <PlayerSearchEntry />
    <Suspense fallback={<SampleMatchPending />}><SampleMatchLink /></Suspense>
  </EntryShell>;
}
