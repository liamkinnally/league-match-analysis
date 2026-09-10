import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { MatchLoading, RouteUnavailable } from "./route-state";

it("keeps navigation accessible while match data is loading without fabricated results", () => {
  render(<MatchLoading />);
  expect(screen.getByRole("status")).toHaveTextContent("Loading results and timeline");
  expect(screen.getByRole("link", { name: "Player search" })).toHaveAttribute("href", "/search");
  expect(screen.queryByText(/Victory|Defeat/)).not.toBeInTheDocument();
});
it("retries a failed route and offers an independent path to player search", () => {
  const reset = vi.fn();
  render(<RouteUnavailable title="This match could not be loaded" description="Try again." onRetry={reset} />);
  fireEvent.click(screen.getByRole("button", { name: "Try again" }));
  expect(reset).toHaveBeenCalledOnce();
  expect(screen.getByRole("link", { name: "Find a player" })).toHaveAttribute("href", "/search");
});
