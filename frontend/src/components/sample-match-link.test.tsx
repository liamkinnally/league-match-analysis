import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { demoFixture } from "../test/match-development-fixture";
import { SampleMatchLink } from "./sample-match-link";

vi.mock("server-only", () => ({}));
it("links to the marked backend demo and identifies its invented data", async () => {
  vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080");
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(demoFixture)));
  render(await SampleMatchLink());
  expect(screen.getByRole("link", { name: "Explore sample match" })).toHaveAttribute("href", "/matches/NA1_7000000001/development?focus=6&compare=1");
  expect(screen.getByText(/Invented data/)).toBeVisible();
});
it("does not offer a broken sample link when the demo is unavailable", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 404 })));
  render(await SampleMatchLink());
  expect(screen.getByText("Sample match unavailable")).toBeVisible();
  expect(screen.queryByRole("link")).not.toBeInTheDocument();
});
