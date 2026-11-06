"use client";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { parseLeaderboard, type LeaderboardSnapshot, type LeaderboardCutoffs } from "../lib/leaderboard/types";
import { profileHref } from "../lib/player-lookup/regions";
import { useCurrentProfileAssets } from "../lib/game-assets/use-game-assets";
import type { GameAssetCatalog } from "../lib/game-assets/types";
import { GameAssetIcon } from "./game-asset-icon";

export function Leaderboard() {
  const [snapshot, setSnapshot] = useState<LeaderboardSnapshot | null>(null);
  const [failed, setFailed] = useState(false);
  const assets = useCurrentProfileAssets();
  const [page, setPage] = useState(1);
  const tableRef = useRef<HTMLTableElement>(null);
  const pageSize = 100;
  const total = snapshot?.entries.length ?? 0;
  const pageCount = Math.ceil(total / pageSize);
  const pageStart = (page - 1) * pageSize;
  const entries = snapshot?.entries.slice(pageStart, pageStart + pageSize) ?? [];
  const selectPage = (nextPage: number) => {
    if (nextPage === page) return;
    setPage(nextPage);
    tableRef.current?.focus({ preventScroll: true });
    tableRef.current?.scrollIntoView?.({ block: "start", behavior: "instant" });
  };
  useEffect(() => {
    const controller = new AbortController();
    let disposed = false;
    const timeout = setTimeout(() => controller.abort(), 12_000);
    void fetch("/api/leaderboard", { signal: controller.signal, cache: "no-store" })
      .then(async response => { if (!response.ok) throw new Error("UNAVAILABLE"); return parseLeaderboard(await response.json()); })
      .then(value => { if (!controller.signal.aborted) setSnapshot(value); })
      .catch(() => { if (!disposed) setFailed(true); })
      .finally(() => clearTimeout(timeout));
    return () => { disposed = true; clearTimeout(timeout); controller.abort(); };
  }, []);
  const unavailable = failed || snapshot?.status === "unavailable";
  return <section className="leaderboard" aria-labelledby="leaderboard-title">
    <div className="entry-heading">
      <h1 id="leaderboard-title">NA leaderboard</h1>
      <p>All NA Challenger players in ranked Solo/Duo. Open a player to explore their profile and match history.</p>
    </div>
    {!snapshot && !failed ? <p role="status">Loading standings…</p> : null}
    {unavailable ? <p role="status" className="entry-notice">Leaderboard is temporarily unavailable.</p> : null}
    {snapshot ? <>
      <PromotionCutoffs cutoffs={snapshot.cutoffs} assets={assets} />
      <div className="leaderboard__meta">
        <span>{snapshot.entries.length} players</span>
        {snapshot.fetchedAt ? <span>Standings updated <time dateTime={snapshot.fetchedAt}>{new Date(snapshot.fetchedAt).toLocaleString("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit", timeZoneName: "short" })}</time></span> : null}
      </div>
      {snapshot.stale ? <p role="status" className="entry-notice">Standings may be out of date. Showing the last successful snapshot.</p> : null}
      {!snapshot.entries.length && snapshot.status !== "unavailable" ? <p className="entry-notice" role="status">{snapshot.status === "pending" ? "The first standings snapshot is being collected." : "No ranked players in this snapshot."}</p> : null}
      {snapshot.entries.length ? <>
        <table ref={tableRef} tabIndex={-1} className="leaderboard__table">
          <caption className="profile-sr-only">North America ranked Solo/Duo standings</caption>
          <thead><tr><th scope="col">#</th><th scope="col">Player</th><th scope="col">Rank / LP</th><th scope="col">W / L</th><th scope="col">Win rate</th></tr></thead>
          <tbody>{entries.map(row => <tr key={row.position}>
            <td className="leaderboard__position">{row.position}</td>
            <td><div className="leaderboard__player">
              <GameAssetIcon asset={row.profileIconId === null ? undefined : assets?.profileIcons?.[String(row.profileIconId)]} fallback="?" className="leaderboard__icon" />
              {row.gameName !== null && row.tagLine !== null ? <Link prefetch={false} href={profileHref({ platform: "NA1", gameName: row.gameName, tagLine: row.tagLine })}><strong>{row.gameName}</strong><span>#{row.tagLine}</span></Link> : <span className="leaderboard__pending">Player name pending</span>}
            </div></td>
            <td><div className="leaderboard__rank"><GameAssetIcon asset={assets?.ranks?.[row.tier]} fallback={row.tier.slice(0, 1)} className="leaderboard__emblem" /><div><span className="leaderboard__tier">{row.tier.charAt(0) + row.tier.slice(1).toLowerCase()}</span><span className="leaderboard__number">{row.leaguePoints.toLocaleString("en-US")} LP</span></div></div></td>
            <td className="leaderboard__number">{row.wins} / {row.losses}</td>
            <td className="leaderboard__number">{row.wins + row.losses ? `${(100 * row.wins / (row.wins + row.losses)).toFixed(1)}%` : "—"}</td>
          </tr>)}</tbody>
        </table>
        {pageCount > 1 ? <nav className="leaderboard__pagination" aria-label="Leaderboard pages">
          <span className="leaderboard__page-range" role="status">{pageStart + 1}–{Math.min(pageStart + pageSize, total)} of {total}</span>
          <div className="leaderboard__page-buttons">{Array.from({ length: pageCount }, (_, index) => index + 1).map(number =>
            <button key={number} type="button" aria-label={`Page ${number}`} aria-current={page === number ? "page" : undefined} onClick={() => selectPage(number)}>{number}</button>
          )}</div>
        </nav> : null}
        <p className="leaderboard__note">Players are sorted by LP, with ties sometimes ordered differently from the League client. Names and icons may update a little later.</p>
      </> : null}
    </> : null}
  </section>;
}

function PromotionCutoffs({ cutoffs, assets }: { cutoffs: LeaderboardCutoffs; assets: GameAssetCatalog | null }) {
  return <section className="leaderboard__cutoffs" aria-labelledby="cutoff-heading">
    <div className="leaderboard__cutoff-heading"><h2 id="cutoff-heading">Estimated promotion cutoffs</h2>
      {cutoffs.fetchedAt ? <span>Updated <time dateTime={cutoffs.fetchedAt}>{new Date(cutoffs.fetchedAt).toLocaleString("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit", timeZoneName: "short" })}</time></span> : null}
    </div>
    {cutoffs.status === "ready" ? <>
      <div className="leaderboard__cutoff-values">{([
        ["CHALLENGER", "Challenger", cutoffs.challenger], ["GRANDMASTER", "Grandmaster", cutoffs.grandmaster],
      ] as const).map(([tier, label, cutoff]) => cutoff ? <div className="leaderboard__cutoff" key={tier}>
        <GameAssetIcon asset={assets?.ranks?.[tier]} fallback={label.slice(0, 1)} className="leaderboard__emblem" />
        <div className="leaderboard__cutoff-data"><h3>{label}</h3>
          <strong>{cutoff.boundaryLp === null ? "Minimum: " : ""}{(cutoff.boundaryLp ?? cutoff.promotionLp).toLocaleString("en-US")} LP</strong>
          <span className="leaderboard__cutoff-slots">{cutoff.slots} slots</span>
          {cutoff.boundaryLp !== null ? <small>Target: {cutoff.promotionLp.toLocaleString("en-US")} LP</small> : null}
        </div>
      </div> : null)}</div>
      {cutoffs.stale ? <p role="status">Cutoff estimates may be out of date. Showing the last complete estimate.</p> : null}
      <p>Riot updates who’s in Challenger and Grandmaster once a day. The estimated cutoffs can change as players gain or lose LP.</p>
    </> : <p role="status">{cutoffs.status === "pending" ? "Promotion cutoff estimates are being collected." : "Promotion cutoff estimates are unavailable."}</p>}
  </section>;
}
