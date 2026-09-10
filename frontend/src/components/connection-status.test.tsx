import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import ConnectionStatus from "./connection-status";

it("shows checking and then connected after a successful response", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
    Response.json({ status: "UP" }),
  ));
  render(<ConnectionStatus />);
  expect(screen.getByRole("status")).toHaveTextContent("Checking connection...");
  expect(await screen.findByText("Backend connected.")).toBeInTheDocument();
});

it("shows unavailable for an unhealthy response", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
    Response.json({ status: "DOWN" }, { status: 503 }),
  ));
  render(<ConnectionStatus />);
  expect(await screen.findByText("Backend unavailable.")).toBeInTheDocument();
});

it("shows unavailable when the request fails", async () => {
  vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
  render(<ConnectionStatus />);
  expect(await screen.findByText("Backend unavailable.")).toBeInTheDocument();
});
