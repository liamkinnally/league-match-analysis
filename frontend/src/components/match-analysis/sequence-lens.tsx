import type { SequenceLens as SequenceLensValue } from "../../lib/analysis/types";

type Props = {
  lens: SequenceLensValue;
};

function clock(ms: number): string {
  const seconds = Math.floor(ms / 1_000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

export function SequenceLens({ lens }: Props) {
  return (
    <section className="sequence-lens" aria-labelledby="sequence-lens-title">
      <div className="lens-heading">
        <p className="eyebrow">Bounded sequence</p>
        <h3 id="sequence-lens-title">Observed event order</h3>
      </div>
      <ol className="sequence-bands">
        {lens.bands.map((band) => (
          <li
            className={band.parallel ? "sequence-band sequence-band--parallel" : "sequence-band"}
            data-parallel={String(band.parallel)}
            data-testid="sequence-band"
            key={band.id}
          >
            <div className="sequence-band__time">
              <time dateTime={`PT${Math.floor(band.startMs / 1_000)}S`}>
                {clock(band.startMs)}
              </time>
              {band.parallel ? <span>Parallel observations</span> : null}
            </div>
            <ul>
              {band.anchors.map((anchor, index) => (
                <li key={`${band.id}-${anchor.representedAtMs}-${index}`}>
                  {anchor.descriptor ?? anchor.kind.replaceAll("_", " ")}
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ol>
      {lens.relations.length > 0 ? (
        <dl className="sequence-relations">
          {lens.relations.map((relation, index) => (
            <div key={`${relation.fromBandId}-${relation.toBandId}-${index}`}>
              <dt>{relation.fromBandId} → {relation.toBandId}</dt>
              <dd>{relation.relation.replaceAll("_", " ")}</dd>
            </div>
          ))}
        </dl>
      ) : null}
    </section>
  );
}
