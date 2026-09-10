import Link from "next/link";
import {
  toAnalysisHref,
  toPanelCloseHref,
  withPanel,
} from "../../lib/analysis/route-context";
import type {
  ActiveAnalysis,
  AnalysisRouteContext,
} from "../../lib/analysis/types";
import { AskSheet } from "./ask-sheet";
import { EvidenceSheet } from "./evidence-sheet";
import { LensStage } from "./lens-stage";
import { StateReceipt } from "./state-receipt";

type Props = {
  active: ActiveAnalysis;
  route: AnalysisRouteContext;
};

function clock(ms: number): string {
  const seconds = Math.floor(ms / 1_000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

function questionText(active: ActiveAnalysis): string {
  return (
    active.supportedQuestions.find(
      (question) => question.id === active.context.questionId,
    )?.prompt ?? active.supportedQuestions[0]?.prompt ?? active.context.questionId
  );
}

function returnLink(route: AnalysisRouteContext) {
  const target = route.returnTarget;
  if (!target) {
    return {
      href: toAnalysisHref({
        ...route,
        mode: "explore",
        selectedObjectId: undefined,
        interval: undefined,
        questionId: undefined,
        evidenceRevision: undefined,
        requestedLens: undefined,
        returnTarget: undefined,
        panel: undefined,
      }),
      label: "Back to Match Arc",
    };
  }

  return {
    href: toAnalysisHref({
      ...route,
      mode: target.mode,
      selectedObjectId: target.objectId ?? route.selectedObjectId,
      panel: undefined,
    }),
    label: target.mode === "review" ? "Back to Review" : "Back to Match Arc",
  };
}

export function Investigation({ active, route }: Props) {
  const back = returnLink(route);
  const evidenceHref = toAnalysisHref(withPanel(route, "evidence"));
  const askHref = toAnalysisHref(withPanel(route, "ask"));
  const closeHref = toPanelCloseHref(route);
  const showsReceiptInLens =
    active.lens.type === "STATE" || active.lens.type === "RECEIPT";

  return (
    <section className="investigation" aria-labelledby="investigation-title">
      <header className="investigation__heading">
        <div>
          <p className="eyebrow">Investigation</p>
          <h2 id="investigation-title">{questionText(active)}</h2>
          <p className="investigation__interval">
            {clock(active.context.interval.startMs)}–{clock(active.context.interval.endMs)}
          </p>
        </div>
        <Link href={back.href}>{back.label}</Link>
      </header>
      <nav className="investigation__actions" aria-label="Investigation details">
        <Link href={evidenceHref}>Evidence</Link>
        <Link href={askHref}>Ask</Link>
      </nav>
      <LensStage active={active} route={route} />
      {showsReceiptInLens || route.mode === "review" ? null : (
        <StateReceipt receipt={active.receipt} />
      )}
      {route.panel === "evidence" ? (
        <EvidenceSheet active={active} closeHref={closeHref} />
      ) : null}
      {route.panel === "ask" ? (
        <AskSheet active={active} closeHref={closeHref} />
      ) : null}
    </section>
  );
}
