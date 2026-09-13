import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { DeferredTimeline } from "./deferred-timeline";
const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
const status = (state: string) => Response.json({ matchId: "NA1_1", runId: null, status: state, retryNotBefore: null });
afterEach(() => { vi.useRealTimers(); refresh.mockClear(); });
it("starts missing timeline work, polls, and refreshes data once when available", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn().mockResolvedValueOnce(status("NOT_REQUESTED")).mockResolvedValueOnce(status("RUNNING")).mockResolvedValueOnce(status("AVAILABLE"));
  vi.stubGlobal("fetch", fetcher);
  await act(async () => render(<DeferredTimeline matchId="NA1_1" />));
  expect(fetcher.mock.calls.map(call => call[1].method)).toEqual(["GET", "POST"]);
  expect(screen.getByRole("status")).toHaveTextContent("Loading timeline");
  await act(async () => vi.advanceTimersByTime(2000));
  expect(refresh).toHaveBeenCalledTimes(1);
  await act(async () => vi.advanceTimersByTime(10000));
  expect(fetcher).toHaveBeenCalledTimes(3);
});
it("stops on terminal failure and only retries on request", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn().mockResolvedValueOnce(status("FAILED")).mockResolvedValueOnce(status("UNAVAILABLE"));
  vi.stubGlobal("fetch", fetcher);
  await act(async () => render(<DeferredTimeline matchId="NA1_1" />));
  await act(async () => vi.advanceTimersByTime(10000));
  expect(fetcher).toHaveBeenCalledTimes(1);
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "Retry timeline" })));
  expect(fetcher.mock.calls[1][1].method).toBe("POST");
  expect(screen.getByRole("heading", { name: "Timeline unavailable" })).toBeVisible();
  expect(screen.queryByRole("button")).not.toBeInTheDocument();
});
it("preserves a retry notice after a request error and aborts work on unmount", async () => {
  const fetcher = vi.fn().mockRejectedValueOnce(new Error("offline")).mockImplementationOnce((_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")));
  }));
  vi.stubGlobal("fetch", fetcher);
  const view = render(<DeferredTimeline matchId="NA1_1" />);
  await act(async () => {});
  expect(screen.getByRole("alert")).toHaveTextContent("Final statistics remain available");
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "Retry timeline" })));
  const signal = fetcher.mock.calls[1][1].signal as AbortSignal;
  view.unmount();
  expect(signal.aborted).toBe(true);
  expect(refresh).not.toHaveBeenCalled();
});
it("refreshes each match once when a reused component receives another match", async () => {
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async (url: string) => Response.json({ matchId: url.includes("NA1_2") ? "NA1_2" : "NA1_1", runId: null, status: "AVAILABLE", retryNotBefore: null })));
  const view = render(<DeferredTimeline matchId="NA1_1" />);
  await act(async () => {});
  expect(refresh).toHaveBeenCalledTimes(1);
  await act(async () => view.rerender(<DeferredTimeline matchId="NA1_2" />));
  expect(refresh).toHaveBeenCalledTimes(2);
  await act(async () => view.rerender(<DeferredTimeline matchId="NA1_2" />));
  expect(refresh).toHaveBeenCalledTimes(2);
});
