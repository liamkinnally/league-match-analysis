import Link from "next/link";
import type { ReviewBeat, ReviewEpisode as ReviewEpisodeValue } from "../../lib/analysis/types";

type Props = {
  episode: ReviewEpisodeValue;
  investigation?: {
    beatId: string;
    href: string;
  };
};

type BeatProps = {
  beat: ReviewBeat;
  investigationHref?: string;
  showTitle?: boolean;
};

const ASSERTION_MODE_LABELS: Record<string, string> = {
  OBSERVED: "Observed",
  RECONSTRUCTED: "Reconstructed",
  INTERPRETED: "Interpretation",
  EXPERT_MAINTAINED: "Expert knowledge",
  UNKNOWN: "Unknown",
};

function assertionModeLabel(assertionMode: string): string {
  return (
    ASSERTION_MODE_LABELS[assertionMode] ??
    assertionMode.toLocaleLowerCase().replaceAll("_", " ")
  );
}

function ReviewBeatView({ beat, investigationHref, showTitle = true }: BeatProps) {
  return (
    <article
      aria-label={showTitle ? undefined : beat.title}
      className="review-beat"
      data-testid="review-beat"
      id={beat.id}
    >
      <p className="review-beat__kind">{beat.kind.replaceAll("_", " ")}</p>
      <p className="review-beat__assertion">
        Assertion: {assertionModeLabel(beat.assertionMode)}
      </p>
      {showTitle ? <h3>{beat.title}</h3> : null}
      <p>{beat.detail}</p>
      {beat.sourceClaimId ? (
        <p className="review-beat__claim">Claim {beat.sourceClaimId}</p>
      ) : null}
      {investigationHref ? (
        <Link className="review-beat__investigate" href={investigationHref}>
          Investigate
        </Link>
      ) : null}
      {beat.limitations.length > 0 ? (
        <ul aria-label={`Limitations for ${beat.title}`}>
          {beat.limitations.map((limitation) => (
            <li key={limitation}>{limitation}</li>
          ))}
        </ul>
      ) : null}
    </article>
  );
}

export function ReviewEpisode({ episode, investigation }: Props) {
  const interpretation = episode.beats.find(
    (beat) => beat.kind === "INTERPRETATION",
  );
  const competingReading = episode.beats.find(
    (beat) => beat.kind === "COMPETING_READING",
  );

  return (
    <div className="review-beats" aria-label="Case beats">
      {episode.beats.map((beat) => {
        if (beat === competingReading && interpretation) return null;

        if (beat === interpretation) {
          return (
            <div
              className="review-interpretation"
              data-testid="review-interpretation"
              key={beat.id}
            >
              <ReviewBeatView
                beat={beat}
                investigationHref={
                  investigation?.beatId === beat.id ? investigation.href : undefined
                }
              />
              {competingReading ? (
                <details className="review-competing-reading">
                  <summary>{competingReading.title}</summary>
                  <ReviewBeatView
                    beat={competingReading}
                    investigationHref={
                      investigation?.beatId === competingReading.id
                        ? investigation.href
                        : undefined
                    }
                    showTitle={false}
                  />
                </details>
              ) : null}
            </div>
          );
        }

        return (
          <ReviewBeatView
            beat={beat}
            investigationHref={
              investigation?.beatId === beat.id ? investigation.href : undefined
            }
            key={beat.id}
          />
        );
      })}
    </div>
  );
}
