import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { PlayerSearchForm } from "./player-search-form";

vi.mock("../lib/game-assets/use-game-assets", () => ({ useCurrentProfileAssets: () => null }));
afterEach(() => { vi.useRealTimers(); });
const suggestion = (gameName: string, platform = "NA1") => ({ gameName, tagLine: "000", platform, profileIconId: null, summonerLevel: 50 });

it("updates cached suggestions per character, limits to five, and selects by keyboard", async () => {
  vi.useFakeTimers();
  const submit = vi.fn();
  const fetcher = vi.fn().mockImplementation(async () => Response.json({ suggestions: [suggestion("Kiting"), suggestion("Kitten")] }));
  vi.stubGlobal("fetch", fetcher);
  render(<PlayerSearchForm onSubmit={submit} submitting={false} />);
  const input = screen.getByRole("combobox", { name: "Riot ID" });
  fireEvent.focus(input); fireEvent.change(input, { target: { value: "k" } });
  await act(async () => vi.advanceTimersByTime(180));
  expect(fetcher.mock.calls[0][0]).toBe("/api/player-suggestions?platform=NA1&q=k");
  expect(screen.getAllByRole("option").filter(option => option.parentElement?.getAttribute("role") === "listbox")).toHaveLength(2);
  fireEvent.change(input, { target: { value: "ki" } });
  await act(async () => vi.advanceTimersByTime(180));
  expect(fetcher.mock.calls[1][0]).toBe("/api/player-suggestions?platform=NA1&q=ki");
  fireEvent.keyDown(input, { key: "ArrowDown" }); fireEvent.keyDown(input, { key: "Enter" });
  expect(submit).toHaveBeenCalledWith({ gameName: "Kiting", tagLine: "000", platform: "NA1", queueId: 0 });
  expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
});

it("discards stale results after region or text changes and supports full uncached IDs", async () => {
  vi.useFakeTimers();
  let finish!: (response: Response) => void;
  const fetcher = vi.fn().mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve; }))
    .mockImplementation(async () => Response.json({ suggestions: [] }));
  vi.stubGlobal("fetch", fetcher);
  const submit = vi.fn();
  render(<PlayerSearchForm onSubmit={submit} submitting={false} />);
  const input = screen.getByRole("combobox", { name: "Riot ID" });
  fireEvent.focus(input); fireEvent.change(input, { target: { value: "player" } });
  await act(async () => vi.advanceTimersByTime(180));
  fireEvent.change(screen.getByRole("combobox", { name: "Region" }), { target: { value: "KR" } });
  await act(async () => vi.advanceTimersByTime(180));
  await act(async () => finish(Response.json({ suggestions: [suggestion("Player")] })));
  expect(screen.queryByRole("option", { name: /Player/ })).not.toBeInTheDocument();
  expect(screen.getByText(/No cached profiles/)).toBeVisible();
  fireEvent.change(input, { target: { value: "다른 이름#KR1" } });
  fireEvent.click(screen.getByRole("button", { name: "Find matches" }));
  expect(submit).toHaveBeenCalledWith({ gameName: "다른 이름", tagLine: "KR1", platform: "KR", queueId: 0 });
});

it("requires a full Riot ID when no suggestion is selected", () => {
  const submit = vi.fn();
  render(<PlayerSearchForm onSubmit={submit} submitting={false} />);
  fireEvent.change(screen.getByRole("combobox", { name: "Riot ID" }), { target: { value: "player" } });
  fireEvent.click(screen.getByRole("button", { name: "Find matches" }));
  expect(screen.getByRole("alert")).toHaveTextContent("Enter the full Riot ID");
  expect(submit).not.toHaveBeenCalled();
});
