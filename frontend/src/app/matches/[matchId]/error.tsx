"use client";

import { RouteUnavailable } from "../../../components/route-state";

export default function MatchError({ reset }: { error: Error; reset: () => void }) {
  return <RouteUnavailable title="This match could not be loaded" description="The match service may be temporarily unavailable. Try again in a moment." onRetry={reset} />;
}
