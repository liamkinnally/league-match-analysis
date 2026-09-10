import Link from "next/link";
import {
  toAnalysisHref,
  withRequestedLens,
} from "../../lib/analysis/route-context";
import type {
  ActiveAnalysis,
  AnalysisRouteContext,
  LensType,
} from "../../lib/analysis/types";
import { MapLens } from "./map-lens";
import { SequenceLens } from "./sequence-lens";
import { StateLens } from "./state-lens";
import { StateReceipt } from "./state-receipt";
import { TransferLens } from "./transfer-lens";
import { ChampionTimingLens } from "./champion-timing-lens";

type Props = {
  active: ActiveAnalysis;
  route: AnalysisRouteContext;
};

const LENS_LABELS: Record<LensType, string> = {
  MAP: "Map",
  SEQUENCE: "Sequence",
  STATE: "State",
  CHAMPION_TIMING: "Champion timing",
  TRANSFER: "Transfer",
  RECEIPT: "Receipt",
};

function payload(active: ActiveAnalysis) {
  const lens = active.lens;
  switch (lens.type) {
    case "MAP":
      return <MapLens lens={lens} />;
    case "SEQUENCE":
      return <SequenceLens lens={lens} />;
    case "STATE":
      return <StateLens lens={lens} />;
    case "CHAMPION_TIMING":
      return <ChampionTimingLens payload={lens} />;
    case "TRANSFER":
      return <TransferLens lens={lens} />;
    case "RECEIPT":
      return <StateReceipt receipt={lens.receipt} />;
  }
}

export function LensStage({ active, route }: Props) {
  return (
    <div className="lens-stage">
      <nav className="lens-switch" aria-label="Analysis lenses">
        {active.context.availableLenses.map((lens) => (
          <Link
            aria-current={active.lens.type === lens ? "page" : undefined}
            href={toAnalysisHref(withRequestedLens(route, lens))}
            key={lens}
          >
            {LENS_LABELS[lens]}
          </Link>
        ))}
      </nav>
      {active.limitations.includes("REQUESTED_LENS_UNAVAILABLE") ? (
        <p className="lens-fallback" role="status">
          The requested lens is not supported by this evidence. Showing the
          timestamped receipt instead.
        </p>
      ) : null}
      <section
        className="analysis-stage"
        data-lens={active.lens.type}
        data-testid="analysis-stage"
        aria-label={`${LENS_LABELS[active.lens.type]} analysis`}
      >
        {payload(active)}
      </section>
    </div>
  );
}
