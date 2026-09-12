import { Suspense } from "react";
import { PlayerSearchEntry } from "../../components/player-search-entry";
import { EntryShell } from "../../components/entry-shell";
import { SampleMatchLink, SampleMatchPending } from "../../components/sample-match-link";
import { isSamplePreview } from "../../lib/preview-mode";

export default async function SearchPage({ searchParams }: { searchParams: Promise<{ runId?: string | string[] }> }) {
  const params = await searchParams;
  const runId = typeof params.runId === "string" ? params.runId : undefined;
  return <EntryShell active="search">
    <header className="entry-heading">
      <p className="development-kicker">Match history</p>
      <h1>Player search</h1>
      <p>{isSamplePreview() ? "Explore the interface using an invented match." : "Find recent ranked matches by Riot ID."}</p>
    </header>
    <PlayerSearchEntry key={runId ?? "new"} initialRunId={runId} />
    <Suspense fallback={<SampleMatchPending />}><SampleMatchLink /></Suspense>
  </EntryShell>;
}
