"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { DevelopmentFooter } from "../development-footer";
import { SiteBrand } from "../site-brand";
import { DeferredTimeline } from "./deferred-timeline";
import { AreaTimeline } from "./area-timeline";
import { TeamResults } from "./team-results";
import { AverageRank } from "./ranks";
import { Scoreboard } from "./scoreboard";
import { FinalStatePanel } from "./final-state-panel";
import { EventFeed } from "./event-feed";
import { SampledValues } from "./sampled-values";
import { developmentHref, parseDevelopmentSearch, type FinalStateSelection } from "../../lib/development/route";
import {
  timeLabel as gameTime,
  displayValue,
  metricValue,
} from "../../lib/development/chart-model";
import {
  participantName,
  queueLabel,
  queueShort,
  roleLabel,
} from "../../lib/development/results";
import { useCurrentRanks } from "../../lib/development/use-current-ranks";
import { useGameAssetCatalogs } from "../../lib/game-assets/use-game-assets";
import { matchResult } from "../../lib/match-result";
import type { GameAssetCatalog } from "../../lib/game-assets/types";
import type {
  DevelopmentInterval,
  MatchDevelopment,
  MatchDevelopmentWindow,
  Metric,
} from "../../lib/development/types";

function windowHref(
  data: MatchDevelopment,
  window: MatchDevelopmentWindow,
  metric: Metric,
  finalState: FinalStateSelection,
) {
  return developmentHref(
    data.matchId,
    data.summary.focusParticipantId,
    data.summary.compareParticipantId ?? undefined,
    { from: window.startMs, to: window.endMs },
    metric,
    finalState,
  );
}
function differenceValue(value: number | null) {
  return value === null
    ? "Unavailable"
    : `${value > 0 ? "+" : ""}${value.toLocaleString("en-US")}`;
}

export function MatchDevelopmentView({
  data,
  interval,
  invented,
  assets: initialAssets,
}: {
  data: MatchDevelopment;
  interval: DevelopmentInterval;
  invented: boolean;
  assets?: GameAssetCatalog | null;
}) {
  const search = useSearchParams();
  const router = useRouter();
  const runeState = parseDevelopmentSearch({ historyRunId: search.getAll("historyRunId").length === 1 ? search.get("historyRunId") ?? undefined : undefined, finalView: search.get("finalView") ?? undefined, runeParticipant: search.get("runeParticipant") ?? undefined });
  const finalState: FinalStateSelection = { historyRunId: runeState.historyRunId, finalView: runeState.finalView, runeParticipant: search.get("runeParticipant") ?? undefined };
  const requestedMetric = search.get("metric");
  const metric: Metric =
    requestedMetric === "cs" || requestedMetric === "xp"
      ? requestedMetric
      : "gold";
  const setMetric = (next: Metric) => {
    const url = new URL(window.location.href);
    if (next === "gold") url.searchParams.delete("metric");
    else url.searchParams.set("metric", next);
    window.history.pushState(null, "", url);
  };
  const loadedAssets = useGameAssetCatalogs(
    initialAssets === undefined ? [data.summary.gameVersion] : [],
    { championIds: data.roster.map(person => person.championId), includeRunes: true },
  );
  const assets =
    initialAssets === undefined
      ? (loadedAssets[data.summary.gameVersion] ?? null)
      : initialAssets;
  const ranks = useCurrentRanks(
    data.matchId,
    data.summary.queueId,
    data.roster.map((p) => p.participantId),
    invented,
  );
  const focal = data.roster.find(
    (p) => p.participantId === data.summary.focusParticipantId,
  )!;
  const comparison = data.roster.find(
    (p) => p.participantId === data.summary.compareParticipantId,
  );
  const compared = !!comparison;
  const selectedWindow = data.windows.find(
    (w) => w.startMs === interval.from && w.endMs === interval.to,
  );
  const selectedIsSuggested = data.suggestedWindows.some(
    (w) => w.id === selectedWindow?.id,
  );
  const suggestedIds = new Set(data.suggestedWindows.map((w) => w.id));
  const otherWindows = data.windows.filter((w) => !suggestedIds.has(w.id));
  const first = data.samples.find((s) => s.timestampMs === interval.from),
    last = data.samples.find((s) => s.timestampMs === interval.to);
  const intervalLabel = `${gameTime(interval.from)}–${gameTime(interval.to)}`;
  const result = matchResult(data.summary.win);
  return (
    <main className="development-app">
      <a className="development-skip-link" href="#match-content">
        Skip to match
      </a>
      <header className="development-nav">
        <SiteBrand />
        <nav aria-label="Product navigation">
          <Link href="/">Home</Link>
          <Link href={runeState.historyRunId ? `/search?runId=${runeState.historyRunId}` : "/search"}>Match history</Link>
          <span aria-current="page">Match detail</span>
        </nav>
        <span className="development-region">
          {data.matchId.split("_")[0]} — {queueShort(data.summary.queueId)}
        </span>
      </header>
      <div id="match-content" className="development-page" tabIndex={-1}>
        {invented ? (
          <p className="development-sample-label">
            Sample match — synthetic data
          </p>
        ) : null}
        <section
          className={`development-result match-result ${result.className}`}
        >
          <div className="development-result__state">
            <span className="refined-queue">
              {queueLabel(data.summary.queueId)}
            </span>
            <strong>{result.label}</strong>
            <span>{gameTime(data.summary.durationMs)}</span>
            <AverageRank ranks={ranks} totalPlayers={data.roster.length} />
          </div>
          <div className="development-result__identity">
            <p className="development-kicker">
              {data.summary.queueId !== 450 && <>{roleLabel(focal.teamPosition)} — </>}{data.matchId}
            </p>
            <h1>
              {participantName(focal, assets)}
              {comparison
                ? ` vs ${participantName(comparison, assets)}`
                : " progression"}
            </h1>
            <p>
              {new Intl.DateTimeFormat("en-US", {
                dateStyle: "medium",
                timeZone: "UTC",
              }).format(data.summary.gameCreationMs)}{" "}
              — Patch {data.summary.gameVersion}
            </p>
          </div>
          <dl className="development-result__stats">
            <div>
              <dt>KDA</dt>
              <dd>
                {data.summary.kills} / {data.summary.deaths} /{" "}
                {data.summary.assists}
              </dd>
            </div>
            <div>
              <dt>CS</dt>
              <dd>{data.summary.totalCs}</dd>
            </div>
            <div>
              <dt>Gold earned</dt>
              <dd>{data.summary.goldEarned.toLocaleString("en-US")}</dd>
            </div>
          </dl>
        </section>
        <TeamResults data={data} />
        <section
          className="development-temporal"
          aria-labelledby="timeline-heading"
        >
          <div className="accepted-area-chart">
            {!data.timelineAvailable && !invented ? <DeferredTimeline key={data.matchId} matchId={data.matchId} /> : <AreaTimeline
              data={data}
              interval={interval}
              assets={assets}
              metric={metric}
              onMetric={setMetric}
              finalState={finalState}
            />}
          </div>
          {data.timelineAvailable && data.samples.length ? (
            <>
              <section
                className="development-window-panel"
                aria-labelledby="suggested-windows-heading"
              >
                <div className="development-window-panel__suggestions">
                  <div>
                    <p className="development-kicker">Recorded changes</p>
                    <h3 id="suggested-windows-heading">Suggested windows</h3>
                  </div>
                  {data.suggestedWindows.length ? (
                    <div className="development-window-cards">
                      {data.suggestedWindows.map((window) => {
                        const current = window.id === selectedWindow?.id;
                        return (
                          <Link
                            key={window.id}
                            scroll={false}
                            className={
                              current
                                ? "development-window-card development-window-card--selected"
                                : "development-window-card"
                            }
                            aria-current={current ? "true" : undefined}
                            aria-label={`${gameTime(window.startMs)}–${gameTime(window.endMs)} ${window.summary}`}
                            href={windowHref(data, window, metric, finalState)}
                          >
                            <strong>
                              {gameTime(window.startMs)}–
                              {gameTime(window.endMs)}
                            </strong>
                            <span>{window.summary}</span>
                          </Link>
                        );
                      })}
                    </div>
                  ) : (
                    <p>No suggested windows met the gold-change threshold.</p>
                  )}

                  {otherWindows.length ? (
                    <details className="development-other-windows">
                      <summary>
                        Other recorded intervals{" "}
                        <span className="refined-interval-count">
                          {otherWindows.length} intervals
                        </span>
                      </summary>
                      <nav
                        className="refined-intervals-scroll"
                        tabIndex={0}
                        aria-label={`All ${otherWindows.length} other recorded intervals, scrollable table`}
                      >
                        <table>
                          <thead>
                            <tr>
                              <th>Interval</th>
                            </tr>
                          </thead>
                          <tbody>
                            {otherWindows.map((window) => (
                              <tr key={window.id}>
                                <td>
                                  <Link
                                    href={windowHref(data, window, metric, finalState)}
                                    scroll={false}
                                    aria-current={
                                      window.id === selectedWindow?.id
                                        ? "true"
                                        : undefined
                                    }
                                  >
                                    {gameTime(window.startMs)}–
                                    {gameTime(window.endMs)}
                                  </Link>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </nav>
                    </details>
                  ) : null}
                </div>

                {selectedWindow ? (
                  <div className="development-window-detail">
                    <p className="development-kicker">
                      Selected window — {intervalLabel}
                    </p>
                    {!selectedIsSuggested ? (
                      <p>{selectedWindow.summary}</p>
                    ) : null}
                    <table aria-label="Before and after differences">
                      <thead>
                        <tr>
                          <th>Metric</th>
                          <th>{gameTime(selectedWindow.startMs)}</th>
                          <th>{gameTime(selectedWindow.endMs)}</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr>
                          <th scope="row">CS</th>
                          <td>
                            {differenceValue(
                              selectedWindow.before.csDifference,
                            )}
                          </td>
                          <td>
                            {differenceValue(selectedWindow.after.csDifference)}
                          </td>
                        </tr>
                        <tr>
                          <th scope="row">Gold</th>
                          <td>
                            {differenceValue(
                              selectedWindow.before.goldDifference,
                            )}
                          </td>
                          <td>
                            {differenceValue(
                              selectedWindow.after.goldDifference,
                            )}
                          </td>
                        </tr>
                        <tr>
                          <th scope="row">XP</th>
                          <td>
                            {differenceValue(
                              selectedWindow.before.xpDifference,
                            )}
                          </td>
                          <td>
                            {differenceValue(selectedWindow.after.xpDifference)}
                          </td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                ) : null}
              </section>

              <div
                className="development-endpoints"
                aria-label={`Selected interval ${intervalLabel}`}
              >
                {[first, last].map((sample, index) => (
                  <div key={index}>
                    <span>
                      {index === 0 ? "From" : "To"}:{" "}
                      {gameTime(index === 0 ? interval.from : interval.to)}
                    </span>
                    <strong>
                      {displayValue(
                        sample ? metricValue(sample, metric, compared) : null,
                        metric,
                        compared,
                        true,
                      )}
                    </strong>
                    <small>
                      {participantName(focal, assets)} level{" "}
                      {sample?.focalLevel ?? "unavailable"}
                      {comparison
                        ? ` — ${participantName(comparison, assets)} level ${sample?.compareLevel ?? "unavailable"}`
                        : ""}
                    </small>
                  </div>
                ))}
              </div>
              <div className="development-timeline-grid">
                <SampledValues
                  samples={data.samples}
                  interval={interval}
                  metric={metric}
                  compared={compared}
                />
              </div>
            </>
          ) : null}
          {data.timelineAvailable || data.events.length ? (
            <div className="development-timeline-grid">
              <EventFeed data={data} interval={interval} assets={assets} />
            </div>
          ) : null}
        </section>
        <FinalStatePanel data={data} assets={assets} view={runeState.finalView} participantId={runeState.runeParticipant} invalidSelection={runeState.hasInvalidRuneSelection}
          onChange={next => router.push(developmentHref(data.matchId, data.summary.focusParticipantId, data.summary.compareParticipantId ?? undefined, interval, metric, { ...finalState, ...next }), { scroll: false })}>
        <Scoreboard
          data={data}
          interval={interval}
          assets={assets}
          ranks={ranks}
          metric={metric}
          finalState={finalState}
        />
        </FinalStatePanel>
      </div>
      <DevelopmentFooter />
    </main>
  );
}
