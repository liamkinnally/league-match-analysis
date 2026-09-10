"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import type { ActiveAnalysis, EvidenceClaim } from "../../lib/analysis/types";
import { StateReceipt } from "./state-receipt";

type Props = {
  active: ActiveAnalysis;
  closeHref: string;
};

function clock(ms: number): string {
  const seconds = Math.floor(ms / 1_000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

function ClaimsAnswer({ claims }: { claims: EvidenceClaim[] }) {
  return (
    <ul className="ask-claims">
      {claims.map((claim) => (
        <li key={claim.claimId}>
          <p>{claim.statement}</p>
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

function StructuredAnswer({
  active,
  answerMode,
}: {
  active: ActiveAnalysis;
  answerMode: string;
}) {
  if (answerMode === "RECEIPT" || answerMode === "STATE") {
    return (
      <StateReceipt
        receipt={active.receipt}
        titleId="ask-state-receipt-title"
      />
    );
  }

  if (answerMode === "SELECTION_RATIONALE") {
    return active.linkedReviewEpisode ? (
      <p>{active.linkedReviewEpisode.selectionRationale}</p>
    ) : null;
  }

  if (
    answerMode === "OBSERVED" ||
    answerMode === "RECONSTRUCTED" ||
    answerMode === "UNKNOWN"
  ) {
    const claims = active.claims.filter(
      (claim) => claim.assertionMode === answerMode,
    );
    return (
      <>
        <ClaimsAnswer claims={claims} />
        {answerMode === "UNKNOWN" && active.limitations.length > 0 ? (
          <ul className="ask-limitations" aria-label="Analysis limitations">
            {active.limitations.map((limitation) => (
              <li key={limitation}>{limitation}</li>
            ))}
          </ul>
        ) : null}
      </>
    );
  }

  return null;
}

export function AskSheet({ active, closeHref }: Props) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  const router = useRouter();
  const [selectedQuestionId, setSelectedQuestionId] = useState<string | null>(
    null,
  );
  const selectedQuestion = active.supportedQuestions.find(
    (question) => question.id === selectedQuestionId,
  );
  const currentQuestion =
    active.supportedQuestions.find(
      (question) => question.id === active.context.questionId,
    ) ?? active.supportedQuestions[0];

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
      aria-labelledby="ask-sheet-title"
    >
      <header className="analysis-sheet__heading">
        <div>
          <p className="eyebrow">Bounded questions</p>
          <h2 id="ask-sheet-title" ref={headingRef} tabIndex={-1}>Ask</h2>
        </div>
        <Link href={closeHref}>Close Ask</Link>
      </header>

      <div className="ask-context">
        <p>
          <span>Current question</span>
          <strong>{currentQuestion?.prompt ?? active.context.questionId}</strong>
        </p>
        <p>
          <span>Interval</span>
          <strong>
            {clock(active.context.interval.startMs)}–{clock(active.context.interval.endMs)}
          </strong>
        </p>
      </div>

      {active.supportedQuestions.length === 0 ? (
        <p>
          No additional evidence-backed questions are available for this transition
        </p>
      ) : (
        <div className="ask-options" aria-label="Evidence-backed questions">
          {active.supportedQuestions.map((question) => (
            <button
              aria-pressed={selectedQuestionId === question.id}
              key={question.id}
              onClick={() => setSelectedQuestionId(question.id)}
              type="button"
            >
              {question.prompt}
            </button>
          ))}
        </div>
      )}

      {selectedQuestion ? (
        <section className="ask-answer" aria-live="polite" aria-label={selectedQuestion.prompt}>
          <StructuredAnswer active={active} answerMode={selectedQuestion.answerMode} />
        </section>
      ) : null}
    </aside>
  );
}
