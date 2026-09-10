import { useRef } from "react";
import {
  displayValue,
  metricValue,
  METRICS,
  timeLabel,
} from "../../lib/development/chart-model";
import type {
  DevelopmentInterval,
  MatchDevelopmentSample,
  Metric,
} from "../../lib/development/types";

export function SampledValues({
  samples,
  interval,
  metric,
  compared,
}: {
  samples: MatchDevelopmentSample[];
  interval: DevelopmentInterval;
  metric: Metric;
  compared: boolean;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const center = () => {
    requestAnimationFrame(() => {
      const node = scroller.current;
      if (!node || !node.closest("details")?.open) return;
      const highlighted = node.querySelectorAll<HTMLElement>(
        "tbody .development-value-selected",
      );
      if (!highlighted.length) return;
      const first = highlighted[0].getBoundingClientRect(),
        last = highlighted[highlighted.length - 1].getBoundingClientRect();
      const box = node.getBoundingClientRect(),
        heading = node.querySelector("thead")!.getBoundingClientRect();
      node.scrollTop +=
        (first.top + last.bottom) / 2 -
        (box.top + heading.height + (node.clientHeight - heading.height) / 2);
    });
  };
  const definition = METRICS[metric];
  return (
    <details
      className="development-values"
      onToggle={(event) => {
        if (event.currentTarget.open) center();
      }}
    >
      <summary>
        <span>
          <strong>Sampled values</strong>
          <small>Exact observations; missing samples stay gaps.</small>
        </span>
        <span className="development-values__action">
          Show all {samples.length} sampled values <i aria-hidden="true">›</i>
        </span>
      </summary>
      <div
        ref={scroller}
        className="refined-samples-scroll"
        tabIndex={0}
        role="region"
        aria-label="Sampled values, scrollable table"
      >
        <table
          aria-label={`All sampled ${definition.label.toLowerCase()} values`}
        >
          <thead>
            <tr>
              <th>Time</th>
              <th>
                {definition.label}
                {compared ? " difference" : ""}
              </th>
              <th className="development-level-column">Levels</th>
            </tr>
          </thead>
          <tbody>
            {samples.map((sample) => (
              <tr
                key={sample.timestampMs}
                className={
                  sample.timestampMs >= interval.from &&
                  sample.timestampMs <= interval.to
                    ? "development-value-selected"
                    : undefined
                }
              >
                <th
                  scope="row"
                  className="development-number"
                  title={`Exact recorded timestamp: ${timeLabel(sample.timestampMs, true)}`}
                >
                  {timeLabel(sample.timestampMs)}
                </th>
                <td
                  className={
                    metricValue(sample, metric, compared) === null
                      ? "development-gap"
                      : "development-number"
                  }
                >
                  {displayValue(
                    metricValue(sample, metric, compared),
                    metric,
                    compared,
                    true,
                  )}
                </td>
                <td className="development-level-column development-number">
                  {sample.focalLevel ?? "—"}
                  {compared ? ` / ${sample.compareLevel ?? "—"}` : ""}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}
