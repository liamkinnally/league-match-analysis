import { Suspense } from "react";
import PlayerSearch from "../components/player-search";
import { EntryShell } from "../components/entry-shell";
import { SampleMatchLink, SampleMatchPending } from "../components/sample-match-link";

export const dynamic = "force-dynamic";

export default function Home() {
  return <EntryShell active="home" compact>
    <header className="entry-heading">
      <p className="development-kicker">Match review</p>
      <h1>Find a player</h1>
      <p>Open a recent match to compare opponents and review how the game developed.</p>
    </header>
    <PlayerSearch />
    <Suspense fallback={<SampleMatchPending />}><SampleMatchLink /></Suspense>
  </EntryShell>;
}
