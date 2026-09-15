import { fireEvent, render, screen, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { PlayerProfileHeader, PlayerProfilePanel } from "./player-profile";
import { parsePlayerProfile } from "../lib/player-lookup/profile";
import { profileFixture } from "../lib/player-lookup/profile.test-fixture";

vi.mock("../lib/game-assets/use-game-assets", () => ({ useCurrentProfileAssets: () => ({
  assetVersion: "16.18.1", champions: {}, items: {}, spells: {},
  profileIcons: { "29": { name: "Profile icon 29", imageUrl: "/__e2e-assets/profile-29.png" } },
  ranks: { EMERALD: { name: "Emerald rank", imageUrl: "/__e2e-assets/emerald.png" } },
}) }));
const profile = () => parsePlayerProfile(structuredClone(profileFixture));
const props = () => ({ identity: profileFixture.identity, profile: profile(), loading: false,
  loadingOlder: false, busy: false, issue: null, loadOlder: vi.fn(), reload: vi.fn() });

it("uses the player identity as the page heading and preserves profile artwork and its fallback", () => {
  render(<PlayerProfileHeader {...props()} />);
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Invented Player#DEMO");
  expect(screen.getByLabelText("Level 123")).toHaveTextContent("123");
  expect(screen.queryByText(/Profile fetched/)).not.toBeInTheDocument();
  fireEvent.error(screen.getByRole("img", { name: "Profile icon 29" }));
  expect(screen.getByRole("img", { name: "Profile icon 29" })).toHaveTextContent("In");
});

it("shows the rank emblem, division LP and current record without claiming a season total", () => {
  render(<PlayerProfilePanel {...props()} />);
  const solo = screen.getByRole("region", { name: "Ranked Solo/Duo" });
  expect(within(solo).getByRole("img", { name: "Emerald rank" })).toBeVisible();
  expect(within(solo).getByText("Emerald II", { exact: true })).toBeVisible();
  expect(within(solo).getByText("42", { exact: false, selector: ".profile-rank__lp" })).toHaveTextContent("42 LP");
  expect(within(solo).getByText(/80W.*60L/, { selector: "strong" })).toBeVisible();
  expect(within(solo).getByText(/57.1%/)).toBeVisible();
  expect(screen.getByText("Ranked Flex", { exact: true })).toBeVisible();
  expect(screen.queryByText(/eligibility|reporting period|games.*loaded/i)).not.toBeInTheDocument();
  expect(screen.queryByText("About this record")).not.toBeInTheDocument();
});

it("distinguishes failed rank, successful unranked, and a ranked zero-game record", () => {
  const input = props();
  input.profile.soloRank = { ...input.profile.soloRank, status: "unavailable", tier: null, division: null,
    leaguePoints: null, wins: null, losses: null, winRate: null, fetchedAt: null };
  const view = render(<PlayerProfilePanel {...input} />);
  const solo = screen.getByRole("region", { name: "Ranked Solo/Duo" });
  expect(within(solo).getByText("Rank unavailable", { exact: true })).toBeVisible();
  expect(within(solo).queryByText("Unranked")).not.toBeInTheDocument();
  input.profile.soloRank.status = "unranked";
  view.rerender(<PlayerProfilePanel {...input} />);
  expect(within(solo).getByText("Unranked")).toBeVisible();
  expect(within(solo).queryByText(/win rate/)).not.toBeInTheDocument();
  input.profile.soloRank = { ...profile().soloRank, wins: 0, losses: 0, winRate: null };
  view.rerender(<PlayerProfilePanel {...input} />);
  expect(within(solo).getByText("No ranked games recorded")).toBeVisible();
  expect(within(solo).queryByText(/0%/)).not.toBeInTheDocument();
});

it("retains saved values when profile and rank freshness differ, without attributing LP changes to matches", () => {
  const input = props();
  input.profile.summoner.stale = true;
  input.profile.soloRank.error = "UNAVAILABLE";
  input.profile.rankHistory.observations.push({ ...input.profile.rankHistory.observations[0],
    id: "00000000-0000-0000-0000-000000000011", observedAt: "2026-09-13T10:00:00Z", tier: "PLATINUM", division: "I", leaguePoints: 80 });
  render(<><PlayerProfileHeader {...input} /><PlayerProfilePanel {...input} /></>);
  expect(screen.getByText("Profile may be out of date")).toBeVisible();
  expect(screen.getByText("Update unavailable · Showing saved rank")).toBeVisible();
  expect(screen.getByText(/80W.*60L/, { selector: "strong" })).toBeVisible();
  fireEvent.click(screen.getByText("View rank observations"));
  const list = screen.getByRole("list", { name: "Observed Solo/Duo ranks" });
  expect(within(list).getByText("Platinum I · 80 LP")).toBeVisible();
  expect(within(list).getByText("Emerald II · 42 LP")).toBeVisible();
  expect(within(list).queryByText(/-38|\+62/)).not.toBeInTheDocument();
});

it("only rechecks unavailable profile details when explicitly requested", () => {
  const input = props();
  const view = render(<PlayerProfileHeader {...input} issue={{ message: "Profile details unavailable", retryNotBefore: null }} />);
  expect(input.reload).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Check profile" }));
  expect(input.reload).toHaveBeenCalledOnce();
  view.rerender(<PlayerProfileHeader {...input} busy issue={{ message: "Profile details unavailable", retryNotBefore: null }} />);
  expect(screen.getByRole("button", { name: "Check profile" })).toBeDisabled();
});

it("keeps unranked observations visible and loads older snapshots only on request", () => {
  const input = props();
  input.profile.rankHistory.observations.push({ id: "00000000-0000-0000-0000-000000000011", observedAt: "2026-09-13T10:00:00Z", status: "unranked", tier: null, division: null, leaguePoints: null, wins: null, losses: null, period: null });
  input.profile.rankHistory.nextCursor = "YWJj";
  const view = render(<PlayerProfilePanel {...input} />);
  expect(input.loadOlder).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText("View rank observations"));
  expect(within(screen.getByRole("list", { name: "Observed Solo/Duo ranks" })).getByText("Unranked")).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "Load older observations" }));
  expect(input.loadOlder).toHaveBeenCalledOnce();
  view.rerender(<PlayerProfilePanel {...input} loadingOlder />);
  expect(screen.getByRole("button", { name: "Loading observations…" })).toBeDisabled();
});


it("colors the Solo record below 50 percent without changing the exact threshold or empty record", () => {
  const input = props();
  input.profile.soloRank = { ...input.profile.soloRank, wins: 49, losses: 51 };
  const view = render(<PlayerProfilePanel {...input} />);
  const rate = () => screen.getByText(/win rate/).parentElement;
  expect(rate()).toHaveClass("profile-rank__rate--losing");
  input.profile.soloRank = { ...input.profile.soloRank, wins: 50, losses: 50 };
  view.rerender(<PlayerProfilePanel {...input} />);
  expect(rate()).not.toHaveClass("profile-rank__rate--losing");
  input.profile.soloRank = { ...input.profile.soloRank, wins: 0, losses: 0 };
  view.rerender(<PlayerProfilePanel {...input} />);
  expect(screen.queryByText(/win rate/)).not.toBeInTheDocument();
});

it("shows the rank's own update age and switches to an absolute date only after 24 hours", () => {
  const input = props();
  input.profile.soloRank.stale = true;
  const fetched = Date.parse(input.profile.soloRank.fetchedAt!);
  const view = render(<PlayerProfilePanel {...input} now={fetched + 2 * 3600000} />);
  expect(screen.getByText("2 hours ago")).toHaveAttribute("dateTime", input.profile.soloRank.fetchedAt);
  expect(screen.getByText(/Last updated:/)).toBeVisible();
  expect(screen.queryByText(/Rank may be out of date/)).not.toBeInTheDocument();
  view.rerender(<PlayerProfilePanel {...input} now={fetched + 24 * 3600000} />);
  expect(screen.getByText("24 hours ago")).toBeVisible();
  view.rerender(<PlayerProfilePanel {...input} now={fetched + 24 * 3600000 + 1} />);
  expect(screen.queryByText(/hours ago/)).not.toBeInTheDocument();
  const time = screen.getByText(/Last updated:/).querySelector("time")!;
  expect(time).toHaveAttribute("dateTime", input.profile.soloRank.fetchedAt);
  expect(time.textContent).toContain("2026");
});

it("keeps an unranked Flex result as a plain row even when the cached result is stale", () => {
  const input = props();
  input.profile.flexRank.stale = true;
  render(<PlayerProfilePanel {...input} />);
  const flex = screen.getByRole("region", { name: "Ranked Flex" });
  expect(within(flex).getByText("Unranked")).toBeVisible();
  expect(flex.querySelector("summary")).toBeNull();
  expect(within(flex).queryByText(/out of date/)).not.toBeInTheDocument();
});
