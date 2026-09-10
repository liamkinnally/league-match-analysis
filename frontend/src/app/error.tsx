"use client";
import { RouteUnavailable } from "../components/route-state";
export default function PageError({ reset }: { error: Error; reset: () => void }) {
  return <RouteUnavailable title="This page could not be loaded" description="The connection may have been interrupted. Try again in a moment." onRetry={reset} />;
}
