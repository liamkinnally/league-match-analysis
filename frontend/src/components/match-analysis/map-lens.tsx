import type { MapLens as MapLensValue } from "../../lib/analysis/types";

type Props = {
  lens: MapLensValue;
};

function clock(ms: number): string {
  const seconds = Math.floor(ms / 1_000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

export function MapLens({ lens }: Props) {
  return (
    <section className="map-lens" aria-labelledby="map-lens-title">
      <div className="lens-heading">
        <p className="eyebrow">Timestamped points</p>
        <h3 id="map-lens-title">Observed positions</h3>
      </div>
      <ol className="map-points">
        {lens.points.map((point, index) => (
          <li key={`${point.representedAtMs}-${point.anchorKind}-${index}`}>
            <time dateTime={`PT${Math.floor(point.representedAtMs / 1_000)}S`}>
              {clock(point.representedAtMs)}
            </time>
            <div>
              <strong>{point.descriptor ?? point.anchorKind.replaceAll("_", " ")}</strong>
              <span>
                {point.x.toLocaleString("en-US")} × {point.y.toLocaleString("en-US")}
              </span>
            </div>
          </li>
        ))}
      </ol>
      <p className="lens-caution">
        Points are shown independently; the evidence does not provide a path between them.
      </p>
    </section>
  );
}
