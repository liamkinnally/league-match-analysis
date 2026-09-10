import { Suspense } from "react";
import PlayerSearch from "../../components/player-search";
import { EntryShell } from "../../components/entry-shell";
import { SampleMatchLink, SampleMatchPending } from "../../components/sample-match-link";

export default async function SearchPage({ searchParams }: { searchParams: Promise<{ runId?: string | string[] }> }) {
  const params = await searchParams;
  const runId = typeof params.runId === "string" ? params.runId : undefined;
  return <EntryShell active="search">
    <header className="entry-heading">
      <p className="development-kicker">Match history</p>
      <h1>Player search</h1>
      <p>Find recent ranked matches by Riot ID.</p>
    </header>
    <PlayerSearch key={runId ?? "new"} initialRunId={runId} />
    <Suspense fallback={<SampleMatchPending />}><SampleMatchLink /></Suspense>
  </EntryShell>;
}
