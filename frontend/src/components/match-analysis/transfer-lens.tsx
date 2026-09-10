import type { TransferLens as TransferLensValue } from "../../lib/analysis/types";

type Props = {
  lens: TransferLensValue;
};

function clock(ms: number): string {
  const seconds = Math.floor(ms / 1_000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

function readable(value: string): string {
  return value.replaceAll("_", " ");
}

export function TransferLens({ lens }: Props) {
  return (
    <section className="transfer-lens" aria-labelledby="transfer-lens-title">
      <div className="lens-heading">
        <p className="eyebrow">Category delta</p>
        <h3 id="transfer-lens-title">Observed exchange categories</h3>
      </div>
      <p className="lens-caution">
        These category changes co-occurred in the selected interval; the evidence does
        not establish causation.
      </p>
      <dl className="transfer-rows">
        {lens.rows.map((row, index) => (
          <div key={`${row.representedAtMs}-${row.category}-${index}`}>
            <dt>{readable(row.category)}</dt>
            <dd>
              {readable(row.observedFor)} at{" "}
              <time dateTime={`PT${Math.floor(row.representedAtMs / 1_000)}S`}>
                {clock(row.representedAtMs)}
              </time>
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
