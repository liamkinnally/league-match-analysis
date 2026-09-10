import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import Home from "./page";

vi.mock("server-only", () => ({}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("../components/sample-match-link", () => ({
  SampleMatchLink: () => { throw new Promise(() => {}); },
  SampleMatchPending: () => <p>Loading sample match…</p>,
}));

it("keeps player search available while the optional sample is still loading", () => {
  render(<Home />);
  expect(screen.getByRole("link", { name: "match-analysis-v1" })).toHaveAttribute("href", "/");
  expect(screen.getByRole("button", { name: "Find matches" })).toBeEnabled();
  expect(screen.getByText("Loading sample match…")).toBeVisible();
});
