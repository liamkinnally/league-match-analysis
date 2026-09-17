import { Suspense } from "react";
import { PlayerSearchEntry } from "../../components/player-search-entry";
import { EntryShell } from "../../components/entry-shell";
import { SampleMatchLink, SampleMatchPending } from "../../components/sample-match-link";

export default async function SearchPage({ searchParams }: { searchParams: Promise<{ runId?: string | string[] }> }) {
  const params = await searchParams;
  const runId = typeof params.runId === "string" ? params.runId : undefined;
  return <EntryShell active="search">
    <PlayerSearchEntry initialRunId={runId}>
      <Suspense fallback={<SampleMatchPending />}><SampleMatchLink /></Suspense>
    </PlayerSearchEntry>
  </EntryShell>;
}
