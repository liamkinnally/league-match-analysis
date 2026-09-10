"use client";

import { useMemo } from "react";
import { useRouter } from "next/navigation";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  ReferenceArea,
  ReferenceLine,
  XAxis,
  YAxis,
} from "recharts";
import { ChartContainer, ChartTooltip, ChartTooltipContent } from "../ui/chart";
import { GameAssetIcon } from "../game-asset-icon";
import {
  METRICS,
  chartRows,
  chartScale,
  timeLabel,
  displayValue,
  intervalChangeLabel,
  timeTicks,
} from "../../lib/development/chart-model";
import { participantName, roleLabel } from "../../lib/development/results";
import { developmentHref } from "../../lib/development/route";
import type {
  DevelopmentInterval,
  MatchDevelopment,
  Metric,
} from "../../lib/development/types";
import type { GameAssetCatalog } from "../../lib/game-assets/types";

const metricKeys = Object.keys(METRICS) as Metric[];
function BoundaryLabel({
  viewBox,
  value,
  start,
}: {
  viewBox?: { x?: number; y?: number };
  value: string;
  start?: boolean;
}) {
  if (viewBox?.x === undefined || viewBox.y === undefined) return null;
  return (
    <text
      x={viewBox.x + (start ? -6 : 6)}
      y={viewBox.y + 12}
      textAnchor={start ? "end" : "start"}
      className="graph-boundary-label"
    >
      {value}
    </text>
  );
}
export function AreaTimeline({
  data,
  interval,
  assets,
  metric,
  onMetric,
}: {
  data: MatchDevelopment;
  interval: DevelopmentInterval;
  assets: GameAssetCatalog | null;
  metric: Metric;
  onMetric: (metric: Metric) => void;
}) {
  const router = useRouter();
  const focal = data.roster.find(
    (p) => p.participantId === data.summary.focusParticipantId,
  )!;
  const opponent = data.roster.find(
    (p) => p.participantId === data.summary.compareParticipantId,
  );
  const opponents = data.roster.filter((p) => p.teamId !== focal.teamId);
  const focalName = participantName(focal, assets),
    opponentName = opponent ? participantName(opponent, assets) : null;
  const definition = METRICS[metric],
    compared = !!opponent;
  const rows = useMemo(
    () => chartRows(data.samples, metric, compared),
    [data.samples, metric, compared],
  );
  const scale = useMemo(() => chartScale(rows), [rows]);
  const start = rows.find((r) => r.timestampMs === interval.from),
    end = rows.find((r) => r.timestampMs === interval.to);
  const changeLabel = compared
    ? intervalChangeLabel(start?.value, end?.value)
    : "Recorded interval";
  const firstTime = rows[0]?.timestampMs ?? 0,
    lastTime = rows.at(-1)?.timestampMs ?? data.summary.durationMs;
  const boundaryInset = (lastTime - firstTime) * 0.15;
  const title = `${definition.label} ${compared ? "difference" : "earned"} over time`;
  const axisValue = (value: number) =>
    `${compared && value > 0 ? "+" : value < 0 ? "−" : ""}${Math.abs(value) >= 1000 ? `${Number((Math.abs(value) / 1000).toFixed(1))}k` : Math.abs(value)}`;
  return (
    <div
      className="graph-module"
      data-graph-variant="area"
      data-metric={metric}
    >
      <div className="graph-heading">
        <h2 id="timeline-heading">{title}</h2>
        <div className="graph-tabs" role="tablist" aria-label="Timeline metric">
          {metricKeys.map((key, index) => (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={metric === key}
              tabIndex={metric === key ? 0 : -1}
              onClick={() => onMetric(key)}
              onKeyDown={(event) => {
                const next =
                  event.key === "ArrowRight"
                    ? (index + 1) % 3
                    : event.key === "ArrowLeft"
                      ? (index + 2) % 3
                      : event.key === "Home"
                        ? 0
                        : event.key === "End"
                          ? 2
                          : null;
                if (next === null) return;
                event.preventDefault();
                onMetric(metricKeys[next]);
                event.currentTarget.parentElement
                  ?.querySelectorAll<HTMLButtonElement>("button")
                  [next].focus();
              }}
            >
              {METRICS[key].label}
            </button>
          ))}
        </div>
      </div>
      <div className="graph-context">
        <span className="graph-pair refined-comparison">
          <GameAssetIcon
            asset={assets?.champions[String(focal.championId)]}
            fallback={focalName.slice(0, 2)}
            className="graph-champion"
          />
          <strong>{focalName}</strong>
          <span className="graph-minus">vs</span>
          <label className="refined-opponent">
            <span className="refined-opponent-display" aria-hidden="true">
              <GameAssetIcon
                asset={
                  opponent
                    ? assets?.champions[String(opponent.championId)]
                    : undefined
                }
                fallback="?"
                className="graph-champion"
              />
              <strong>{opponentName ?? "Choose opponent"}</strong>
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                <path
                  d="m3 4.5 3 3 3-3"
                  stroke="currentColor"
                  strokeWidth="1.3"
                />
              </svg>
            </span>
            <select
              aria-label="Compare with opponent"
              value={opponent?.participantId ?? ""}
              onChange={(event) =>
                router.push(
                  developmentHref(
                    data.matchId,
                    focal.participantId,
                    Number(event.target.value),
                    interval,
                    metric,
                  ),
                  { scroll: false },
                )
              }
            >
              <option value="" disabled>
                Choose an opponent
              </option>
              {opponents.map((p) => (
                <option key={p.participantId} value={p.participantId}>
                  {participantName(p, assets)} — {roleLabel(p.teamPosition)}
                </option>
              ))}
            </select>
          </label>
        </span>
        <span
          className="graph-interval"
          title={`${focalName}: ${changeLabel.toLowerCase()}; ${displayValue(start?.value, metric, compared)} to ${displayValue(end?.value, metric, compared)}. Exact interval: ${timeLabel(interval.from, true)}–${timeLabel(interval.to, true)}`}
        >
          <span className="graph-interval-mark" />
          <span>{changeLabel}</span>
          <strong>
            {timeLabel(interval.from)}–{timeLabel(interval.to)}
          </strong>
        </span>
      </div>
      {!data.timelineAvailable || !rows.length ? (
        <div className="development-empty">
          <h3>
            {data.timelineAvailable
              ? "Timeline samples unavailable"
              : "Timeline unavailable"}
          </h3>
          <p>
            {data.timelineAvailable
              ? "No usable participant samples are available. Captured events remain available below."
              : "The result and scoreboard are available, but this match has no captured timeline."}
          </p>
        </div>
      ) : (
        <>
          <div className="graph-plot-head">
            <span>
              {definition.label}
              {compared ? " difference" : ""}
            </span>
            <span>{rows.length} recorded samples</span>
          </div>
          <ChartContainer
            config={{
              value: {
                label: `${definition.label}${compared ? " difference" : ""}`,
                color: "var(--development-accent)",
              },
            }}
            className="graph-chart"
            initialDimension={{ width: 1100, height: 264 }}
          >
            <ComposedChart
              data={rows}
              margin={{ left: 0, right: 16, top: 23, bottom: 4 }}
              accessibilityLayer
            >
              <CartesianGrid
                vertical={false}
                stroke="var(--development-line)"
                strokeOpacity={0.7}
                strokeDasharray="2 5"
              />
              <XAxis
                dataKey="timestampMs"
                type="number"
                scale="linear"
                domain={[
                  firstTime,
                  lastTime === firstTime ? firstTime + 1 : lastTime,
                ]}
                ticks={timeTicks(firstTime, lastTime)}
                allowDataOverflow
                tickLine={false}
                axisLine={false}
                tickMargin={9}
                height={30}
                tickFormatter={(value) => timeLabel(value)}
                minTickGap={18}
              />
              <YAxis
                domain={scale.domain}
                ticks={scale.ticks}
                allowDataOverflow
                tickLine={false}
                axisLine={false}
                tickMargin={10}
                width={55}
                tickFormatter={axisValue}
              />
              <ReferenceArea
                x1={interval.from}
                x2={interval.to}
                fill="var(--development-accent)"
                fillOpacity={0.055}
                stroke="none"
              />
              <ReferenceLine
                y={0}
                stroke="var(--development-muted)"
                strokeOpacity={0.45}
              />
              <Area
                dataKey="value"
                type="linear"
                stroke="var(--color-value)"
                strokeWidth={1.8}
                connectNulls={false}
                isAnimationActive={false}
                fill="var(--color-value)"
                fillOpacity={0.105}
                baseValue={0}
                dot={({ cx, cy, payload }) =>
                  Number.isFinite(payload?.value) &&
                  (payload.timestampMs === interval.from ||
                    payload.timestampMs === interval.to ||
                    payload.isolated) ? (
                    <circle
                      key={payload.timestampMs}
                      cx={cx}
                      cy={cy}
                      r={3}
                      fill="var(--development-panel)"
                      stroke="var(--color-value)"
                      strokeWidth={1.7}
                    />
                  ) : (
                    <g key={payload?.timestampMs} />
                  )
                }
                activeDot={{
                  r: 3.5,
                  fill: "var(--color-value)",
                  stroke: "var(--development-panel)",
                  strokeWidth: 2,
                }}
              />
              <ReferenceLine
                x={interval.from}
                stroke="var(--development-accent)"
                strokeOpacity={0.65}
                strokeDasharray="3 4"
                label={
                  <BoundaryLabel
                    value={timeLabel(interval.from)}
                    start={interval.from > firstTime + boundaryInset}
                  />
                }
              />
              <ReferenceLine
                x={interval.to}
                stroke="var(--development-accent)"
                strokeOpacity={0.65}
                strokeDasharray="3 4"
                label={
                  <BoundaryLabel
                    value={timeLabel(interval.to)}
                    start={interval.to > lastTime - boundaryInset}
                  />
                }
              />
              <ChartTooltip
                isAnimationActive={false}
                filterNull={false}
                cursor={{
                  stroke: "var(--development-muted)",
                  strokeDasharray: "2 3",
                }}
                content={
                  <ChartTooltipContent
                    className="graph-tooltip"
                    hideIndicator
                    labelFormatter={(_value, payload) =>
                      timeLabel(payload?.[0]?.payload?.timestampMs ?? 0, true)
                    }
                    formatter={(_value, _name, item) => (
                      <div className="graph-tooltip-values">
                        <div>
                          <span>
                            {definition.label}
                            {compared ? " difference" : ""}
                          </span>
                          <strong>
                            {displayValue(
                              item.payload.value,
                              metric,
                              compared,
                              true,
                            )}
                          </strong>
                        </div>
                        <div>
                          <span>{focalName}</span>
                          <span>
                            {Number.isFinite(item.payload.focal)
                              ? item.payload.focal.toLocaleString("en-US")
                              : "Unavailable"}
                          </span>
                        </div>
                        {compared ? (
                          <div>
                            <span>{opponentName}</span>
                            <span>
                              {Number.isFinite(item.payload.comparison)
                                ? item.payload.comparison.toLocaleString(
                                    "en-US",
                                  )
                                : "Unavailable"}
                            </span>
                          </div>
                        ) : null}
                      </div>
                    )}
                  />
                }
              />
            </ComposedChart>
          </ChartContainer>
          <div className="graph-caption">
            <span>
              {compared
                ? `Above 0: ${focalName} leads in ${definition.unit}. Below 0: ${opponentName} leads.`
                : `Recorded ${definition.unit} for ${focalName}. Missing observations remain gaps.`}
            </span>
            <span>Game time</span>
          </div>
        </>
      )}
    </div>
  );
}
