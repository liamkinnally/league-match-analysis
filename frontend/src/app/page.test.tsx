import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import Home from "./page";

vi.mock("server-only", () => ({}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

it("offers a sample profile beside live player search without waiting on the backend", () => {
  render(<Home />);
  expect(screen.getByRole("link", { name: "LoL Match Analysis" })).toHaveAttribute("href", "/");
  expect(screen.getByRole("button", { name: "Find matches" })).toBeEnabled();
  expect(screen.getByRole("link", { name: "Explore sample profile" })).toHaveAttribute("href", "/sample/profile");
});
