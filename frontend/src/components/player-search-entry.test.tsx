import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import Home from "../app/page";
import SearchPage from "../app/search/page";
import { SampleMatchLink } from "./sample-match-link";

vi.mock("server-only", () => ({}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

it("makes the sample reachable from entry pages while preventing live search and restored polling", async () => {
  vi.stubEnv("VERCEL", "1");
  vi.stubEnv("VERCEL_ENV", "preview");
  vi.stubEnv("PREVIEW_DATA_SOURCE", "sample");
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  // Render the server-resolved entry content; async sample links are verified separately.
  const home = Home();
  const entry = home.props.children;
  const view = render(<>{entry[0]}{entry[1]}</>);
  expect(screen.getByText(/Live player search is unavailable in this sample preview/)).toBeVisible();
  expect(screen.queryByRole("button", { name: "Find matches" })).not.toBeInTheDocument();
  view.unmount();
  const search = await SearchPage({ searchParams: Promise.resolve({ runId: "00000000-0000-0000-0000-000000000001" }) });
  render(<>{search.props.children[1]}{await SampleMatchLink()}</>);
  expect(screen.getByRole("link", { name: "Explore sample match" })).toHaveAttribute("href", "/matches/__preview_sample/development?focus=6&compare=1");
  expect(fetcher).not.toHaveBeenCalled();
});
