import type { StateReceipt as StateReceiptValue } from "../../lib/analysis/types";

type Props = {
  receipt: StateReceiptValue;
  titleId?: string;
};

function signed(value: number): string {
  const absolute = Math.abs(value).toLocaleString("en-US");
  if (value > 0) return `+${absolute}`;
  if (value < 0) return `−${absolute}`;
  return "0";
}

function clock(ms: number): string {
  const totalSeconds = Math.floor(ms / 1_000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

function sampleLabel(
  position: "Before" | "After",
  consequenceEvidence: boolean | undefined,
): string {
  if (consequenceEvidence == null) return position;
  const evidenceRole = consequenceEvidence
    ? "Consequence evidence"
    : "Antecedent evidence";
  return `${position} · ${evidenceRole}`;
}

export function StateReceipt({ receipt, titleId = "state-receipt-title" }: Props) {
  const before = receipt.before;
  const after = receipt.after;
  const beforeLead = before?.focalTeamLead;
  const afterLead = after?.focalTeamLead;
  const leadSummary =
    beforeLead != null && afterLead != null
      ? `Team lead ${signed(beforeLead)} → ${signed(afterLead)}`
      : "Team lead unavailable";

  return (
    <section
      className="state-receipt"
      aria-labelledby={titleId}
      data-testid="state-receipt"
    >
      <div>
        <p className="eyebrow">Timestamped receipt</p>
        <h3 id={titleId}>
          {leadSummary}
        </h3>
      </div>
      <p className="receipt-samples">
        {before && after
          ? `Sampled at ${clock(before.representedAtMs)} and ${clock(after.representedAtMs)}`
          : `Receipt window ${clock(receipt.interval.startMs)}–${clock(receipt.interval.endMs)}`}
      </p>
      <dl className="receipt-grid">
        <div>
          <dt>{sampleLabel("Before", before?.consequenceEvidence)}</dt>
          <dd>{before ? clock(before.representedAtMs) : "Not observed"}</dd>
        </div>
        <div>
          <dt>{sampleLabel("After", after?.consequenceEvidence)}</dt>
          <dd>{after ? clock(after.representedAtMs) : "Not observed"}</dd>
        </div>
        <div>
          <dt>Change</dt>
          <dd>
            {receipt.focalTeamLeadDelta == null
              ? "Not available"
              : signed(receipt.focalTeamLeadDelta)}
          </dd>
        </div>
      </dl>
      {receipt.limitations.length > 0 ? (
        <p className="limitations">Limited by {receipt.limitations.join(", ")}.</p>
      ) : null}
    </section>
  );
}
