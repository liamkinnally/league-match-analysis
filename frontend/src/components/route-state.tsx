import Link from "next/link";
import { EntryShell } from "./entry-shell";
import { HistorySkeleton, LoadingStatus } from "./loading-state";

export function PageLoading({ search = false }: { search?: boolean }) {
  return <EntryShell active={search ? "search" : undefined} compact={!search}>
    <header className="entry-heading"><p className="development-kicker">Match history</p><h1>{search ? "Loading match history…" : "Loading page…"}</h1></header>
    <LoadingStatus title={search ? "Opening match history…" : "Opening the page…"} description="You can use the navigation above while this loads." />
    <HistorySkeleton />
  </EntryShell>;
}

export function MatchLoading() {
  return <EntryShell>
    <header className="entry-heading"><p className="development-kicker">Match review</p><h1>Loading match…</h1></header>
    <LoadingStatus title="Loading results and timeline…" description="Retrieving this match’s recorded data." />
    <div className="entry-match-skeleton" aria-hidden="true">
      <div className="entry-skeleton-summary"><span className="entry-skeleton entry-skeleton--portrait" /><div><span className="entry-skeleton" /><span className="entry-skeleton entry-skeleton--short" /></div></div>
      <div className="entry-skeleton-chart"><span className="entry-skeleton entry-skeleton--short" /></div>
      <HistorySkeleton />
    </div>
  </EntryShell>;
}

export function RouteUnavailable({ title, description, onRetry }: { title: string; description: string; onRetry?: () => void }) {
  return <EntryShell compact>
    <header className="entry-heading"><p className="development-kicker">Match review</p><h1>{title}</h1><p>{description}</p></header>
    <div className="entry-actions">
      {onRetry && <button className="entry-button" type="button" onClick={onRetry}>Try again</button>}
      <Link className="entry-button" href="/search">Find a player</Link>
      <Link href="/">Return home</Link>
    </div>
  </EntryShell>;
}
