import { notFound } from "next/navigation";
import { EntryShell } from "../../../../components/entry-shell";
import { PlayerSearchEntry } from "../../../../components/player-search-entry";
import { parseProfileRoute, profileHref } from "../../../../lib/player-lookup/regions";
import type { Metadata } from "next";
import { Suspense } from "react";
import { SampleMatchLink, SampleMatchPending } from "../../../../components/sample-match-link";

type Props = { params: Promise<{ region: string; riotId: string }>; searchParams: Promise<{ runId?: string | string[] }> };
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { region, riotId } = await params;
  // Metadata receives decoded params; the page renderer encodes its segment params.
  const identity = parseProfileRoute(region, riotId, { encoded: false });
  if (!identity) return {};
  return { title: `${identity.gameName}#${identity.tagLine} · LoL Match Analysis`, alternates: { canonical: profileHref(identity) } };
}
export default async function ProfilePage({ params, searchParams }: Props) {
  const [{ region, riotId }, search] = await Promise.all([params, searchParams]);
  const identity = parseProfileRoute(region, riotId);
  if (!identity) notFound();
  const runId = typeof search.runId === "string" ? search.runId : undefined;
  return <EntryShell active="search">
    <PlayerSearchEntry initialIdentity={identity} initialRunId={runId} />
    <Suspense fallback={<SampleMatchPending />}><SampleMatchLink /></Suspense>
  </EntryShell>;
}
