"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef } from "react";
import type {
  ActiveAnalysis,
  EvidenceClaim,
} from "../../lib/analysis/types";

type Props = {
  active: ActiveAnalysis;
  closeHref: string;
};

type EvidenceAssertionMode =
  | "OBSERVED"
  | "RECONSTRUCTED"
  | "INTERPRETED"
  | "EXPERT_MAINTAINED"
  | "UNKNOWN";

const ASSERTION_MODE_LABELS: Record<EvidenceAssertionMode, string> = {
  OBSERVED: "Observed",
  RECONSTRUCTED: "Reconstructed",
  INTERPRETED: "Interpretation",
  EXPERT_MAINTAINED: "Expert knowledge",
  UNKNOWN: "Unknown",
};

function clock(ms: number): string {
  const seconds = Math.floor(ms / 1_000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

function ClaimList({ claims }: { claims: EvidenceClaim[] }) {
  return (
    <ul className="evidence-claims">
      {claims.map((claim) => (
        <li key={claim.claimId}>
          <p>{claim.statement}</p>
          <span>{claim.claimId}</span>
          {claim.limitations.length > 0 ? (
            <ul aria-label={`Limitations for ${claim.claimId}`}>
              {claim.limitations.map((limitation) => (
                <li key={limitation}>{limitation}</li>
              ))}
            </ul>
          ) : null}
        </li>
      ))}
    </ul>
  );
}

export function EvidenceSheet({ active, closeHref }: Props) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  const router = useRouter();
  const revision = active.evidence.revision;
  const sequenceRelations =
    active.lens.type === "SEQUENCE" ? active.lens.relations : [];

  useEffect(() => {
    headingRef.current?.focus();
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") router.replace(closeHref);
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [closeHref, router]);

  return (
    <aside
      className="analysis-sheet"
      role="dialog"
      aria-labelledby="evidence-sheet-title"
    >
      <header className="analysis-sheet__heading">
        <div>
          <p className="eyebrow">Disclosure</p>
          <h2 id="evidence-sheet-title" ref={headingRef} tabIndex={-1}>Evidence</h2>
        </div>
        <Link href={closeHref}>Close Evidence</Link>
      </header>

      <section aria-labelledby="evidence-claims-title">
        <h3 id="evidence-claims-title">Claims by assertion mode</h3>
        {Object.entries(ASSERTION_MODE_LABELS).map(([mode, label]) => {
          const claims = active.claims.filter(
            (claim) => claim.assertionMode === mode,
          );
          return claims.length > 0 ? (
            <section className="evidence-mode" aria-labelledby={`evidence-${mode}`} key={mode}>
              <h4 id={`evidence-${mode}`}>{label}</h4>
              <ClaimList claims={claims} />
            </section>
          ) : null;
        })}
      </section>

      <section aria-labelledby="evidence-provenance-title">
        <h3 id="evidence-provenance-title">Provenance</h3>
        <dl className="evidence-records">
          {active.evidence.references.map((reference, index) => (
            <div key={`${reference.sourceCaptureId}-${reference.sourceRecordId}-${index}`}>
              <dt>Source kind</dt>
              <dd>{reference.sourceKind}</dd>
              <dt>Source capture</dt>
              <dd>{reference.sourceCaptureId}</dd>
              <dt>Source record</dt>
              <dd>{reference.sourceRecordId}</dd>
              <dt>Represented time</dt>
              <dd>
                <time dateTime={`PT${Math.floor(reference.representedAtMs / 1_000)}S`}>
                  {clock(reference.representedAtMs)}
                </time>
              </dd>
              <dt>Method version</dt>
              <dd>{reference.methodVersion}</dd>
            </div>
          ))}
        </dl>
      </section>

      <section aria-labelledby="evidence-coverage-title">
        <h3 id="evidence-coverage-title">Coverage and missing predicates</h3>
        <dl className="evidence-coverage">
          {active.evidence.coverage.map((coverage, index) => (
            <div key={`${coverage.signal}-${coverage.sourceRecordId}-${index}`}>
              <dt>{coverage.signal}</dt>
              <dd>
                {coverage.status} · Source {coverage.sourceKind} · Record{" "}
                <span>{coverage.sourceRecordId}</span>
              </dd>
              <dd>
                Source capture{" "}
                <span>{coverage.sourceCaptureId ?? "Unavailable"}</span>
              </dd>
              <dd>
                {coverage.representedStartMs == null || coverage.representedEndMs == null
                  ? "Represented time unknown"
                  : `${clock(coverage.representedStartMs)}–${clock(coverage.representedEndMs)}`}
              </dd>
              <dd>{coverage.methodVersion}</dd>
            </div>
          ))}
        </dl>
      </section>

      {sequenceRelations.length > 0 ? (
        <section aria-labelledby="evidence-relations-title">
          <h3 id="evidence-relations-title">Relations</h3>
          <dl className="evidence-relations">
            {sequenceRelations.map((relation, index) => (
              <div key={`${relation.fromBandId}-${relation.toBandId}-${index}`}>
                <dt>{relation.fromBandId} → {relation.toBandId}</dt>
                <dd>{relation.relation}</dd>
              </div>
            ))}
          </dl>
        </section>
      ) : null}

      {active.limitations.length > 0 ? (
        <section aria-labelledby="evidence-limitations-title">
          <h3 id="evidence-limitations-title">Limitations</h3>
          <ul>
            {active.limitations.map((limitation) => (
              <li key={limitation}>{limitation}</li>
            ))}
          </ul>
        </section>
      ) : null}

      <section aria-labelledby="evidence-versions-title">
        <h3 id="evidence-versions-title">Methods and rules</h3>
        <dl className="evidence-versions">
          <div><dt>Evidence revision</dt><dd>{revision.evidenceRevision}</dd></div>
          <div><dt>Historical methods</dt><dd>{revision.historicalMethodVersions.join(", ")}</dd></div>
          <div><dt>Transition policy</dt><dd>{revision.transitionPolicyVersion}</dd></div>
          <div><dt>Receipt projection</dt><dd>{revision.receiptProjectionVersion}</dd></div>
          <div><dt>Lens policy</dt><dd>{revision.lensPolicyVersion}</dd></div>
          <div><dt>Episode policy</dt><dd>{revision.episodePolicyVersion}</dd></div>
          <div><dt>Review ordering</dt><dd>{revision.reviewOrderingPolicyVersion}</dd></div>
          {revision.championKnowledgeVersion ? (
            <div><dt>Champion knowledge</dt><dd>{revision.championKnowledgeVersion}</dd></div>
          ) : null}
        </dl>
      </section>
    </aside>
  );
}
