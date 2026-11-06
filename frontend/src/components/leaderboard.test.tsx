import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { AnchorHTMLAttributes } from "react";
import { expect, it, vi } from "vitest";
import { Leaderboard } from "./leaderboard";
import { leaderboardFixture } from "../lib/leaderboard/leaderboard.test-fixture";
vi.mock("next/link", () => ({ default: ({ prefetch, ...props }: AnchorHTMLAttributes<HTMLAnchorElement> & { prefetch?: boolean }) => <a {...props} data-prefetch={String(prefetch)} /> }));
vi.mock("../lib/game-assets/use-game-assets", () => ({ useCurrentProfileAssets: () => ({ profileIcons: { "29": { name: "Profile icon 29", imageUrl: "/icon.png" } }, ranks: { CHALLENGER: { name: "Challenger rank", imageUrl: "/challenger.png" } } }) }));
it("renders cached ranks, optional identities and non-prefetched canonical profile links", async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({ ...leaderboardFixture, stale: true })); vi.stubGlobal("fetch", fetcher);
  render(<Leaderboard />);
  const link = await screen.findByRole("link", { name: /Test Player#NA1/ });
  expect(link).toHaveAttribute("href", "/summoners/na/Test%20Player-NA1"); expect(link).toHaveAttribute("data-prefetch", "false");
  expect(screen.getByText("Player name pending").closest("a")).toBeNull();
  expect(screen.getByText("60.0%")).toBeVisible(); expect(screen.getByText("—")).toBeVisible();
  expect(screen.getByText(/Standings may be out of date/)).toBeVisible();
  expect(screen.getByRole("img", { name: "Profile icon 29" })).toHaveAttribute("src", "/icon.png");
  const emblem = screen.getByRole("table").querySelector<HTMLImageElement>(".leaderboard__emblem")!;
  expect(emblem).toHaveAttribute("src", "/challenger.png");
  fireEvent.error(emblem);
  expect(screen.getByRole("table").querySelector<HTMLImageElement>(".leaderboard__emblem")!).toHaveTextContent("C");
  expect(screen.getByText("1,500 LP")).toBeVisible();
  expect(screen.getAllByText("Challenger")[0]).toBeVisible();
  expect(fetcher.mock.calls.every(([url]) => url === "/api/leaderboard")).toBe(true);
});
it("distinguishes empty, pending and sanitized unavailable states", async () => {
  const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
  for (const [status, text] of [["ready", "No ranked players in this snapshot."], ["pending", "The first standings snapshot is being collected."], ["unavailable", "Leaderboard is temporarily unavailable."]]) {
    fetcher.mockResolvedValue(Response.json({ ...leaderboardFixture, status, fetchedAt: status === "ready" ? leaderboardFixture.fetchedAt : null, entries: [] }));
    const view = render(<Leaderboard />); await screen.findByText(text); expect(screen.queryByRole("navigation", { name: "Leaderboard pages" })).toBeNull(); view.unmount();
  }
  fetcher.mockResolvedValue(Response.json({ message: "private failure" }, { status: 500 }));
  render(<Leaderboard />); await waitFor(() => expect(screen.getByText("Leaderboard is temporarily unavailable.")).toBeVisible());
  expect(screen.queryByText(/private/)).toBeNull();
});

it("shows independently dated estimated cutoffs, minimum floors and unavailable states without losing standings", async () => {
  const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
  fetcher.mockResolvedValue(Response.json({ ...leaderboardFixture, cutoffs: { ...leaderboardFixture.cutoffs, stale: true } }));
  const view = render(<Leaderboard />);
  await screen.findByText("1,900 LP");
  expect(screen.getByText("Target: 1,901 LP")).toBeVisible();
  expect(screen.getByText("300 slots")).toBeVisible(); expect(screen.getByText("700 slots")).toBeVisible();
  expect(screen.getByText(/Cutoff estimates may be out of date/)).toBeVisible();
  expect(screen.queryByText(/Standings may be out of date/)).toBeNull();
  expect(document.querySelector('time[datetime="2026-11-06T12:01:00Z"]')).not.toBeNull();
  view.unmount();
  for (const [cutoffs, message] of [
    [{ ...leaderboardFixture.cutoffs, challenger: { slots: 300, minimumLp: 800, boundaryLp: null, promotionLp: 800 } }, "Minimum: 800 LP"],
    [{ status: "pending", fetchedAt: null, stale: false, challenger: null, grandmaster: null }, "Promotion cutoff estimates are being collected."],
    [{ status: "unavailable", fetchedAt: null, stale: false, challenger: null, grandmaster: null }, "Promotion cutoff estimates are unavailable."],
  ] as const) {
    fetcher.mockResolvedValue(Response.json({ ...leaderboardFixture, cutoffs }));
    const current = render(<Leaderboard />); await screen.findByText(message);
    expect(screen.getByRole("link", { name: /Test Player/ })).toBeVisible(); current.unmount();
  }
});

it("pages cached standings in groups of 100 with global positions and no additional lookup", async () => {
  const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
  for (const total of [300, 201]) {
    fetcher.mockResolvedValue(Response.json({ ...leaderboardFixture, entries: Array.from({ length: total }, (_, index) => ({ ...leaderboardFixture.entries[0], position: index + 1, gameName: `Player ${index + 1}` })) }));
    const view = render(<Leaderboard />);
    await screen.findByRole("navigation", { name: "Leaderboard pages" });
    for (const page of [1, 2, 3]) {
      const button = screen.getByRole("button", { name: `Page ${page}` });
      if (page > 1) fireEvent.click(button);
      const table = screen.getByRole("table"), rows = table.querySelectorAll("tbody tr");
      const first = (page - 1) * 100 + 1, last = Math.min(page * 100, total);
      expect(rows).toHaveLength(last - first + 1);
      expect(rows[0].querySelector("td")).toHaveTextContent(String(first));
      expect(rows[rows.length - 1].querySelector("td")).toHaveTextContent(String(last));
      expect(screen.getByText(`${first}–${last} of ${total}`)).toBeVisible();
      expect(button).toHaveAttribute("aria-current", "page");
      expect(table.querySelector("a")).toHaveAttribute("data-prefetch", "false");
      if (page > 1) expect(table).toHaveFocus();
    }
    expect(fetcher).toHaveBeenCalledTimes(1);
    view.unmount(); fetcher.mockClear();
  }
});
