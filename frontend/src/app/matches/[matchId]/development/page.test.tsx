import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import {
  demoFixture,
  developmentFixture,
  partialMetricWindowFixture,
} from "../../../../test/match-development-fixture";
import DevelopmentPage from "./page";

vi.mock("server-only", () => ({}));
const { redirectMock, pushMock } = vi.hoisted(() => ({ redirectMock: vi.fn(), pushMock: vi.fn() }));
vi.mock("next/navigation", () => ({
  notFound: vi.fn(),
  useRouter: () => ({ push: pushMock }),
  useSearchParams: () => new URLSearchParams(),
  redirect: (href: string) => {
    redirectMock(href);
    throw new Error("NEXT_REDIRECT");
  },
}));

it("loads the real development contract into the sample page", async () => {
  vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080");
  vi.stubGlobal("fetch", vi.fn()
    .mockResolvedValueOnce(Response.json(developmentFixture))
    .mockResolvedValueOnce(Response.json(demoFixture))
    .mockResolvedValue(Response.json({})));

  render(await DevelopmentPage({
    params: Promise.resolve({ matchId: "NA1_7000000001" }),
    searchParams: Promise.resolve({ focus: "6", compare: "1", from: "480000", to: "600000" }),
  }));

  expect(screen.getByText("Sample match — synthetic data")).toBeVisible();
  expect(screen.getByRole("heading", { name: "Garen vs Darius" })).toBeVisible();
  expect(screen.getByRole("tab", { name: "Gold" })).toBeVisible();
  expect(screen.getByText(/Garen extended his lead/)).toBeVisible();
});

it("selects the first suggested window when the URL has no interval", async () => {
  vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080");
  vi.stubGlobal("fetch", vi.fn()
    .mockResolvedValueOnce(Response.json(developmentFixture))
    .mockResolvedValueOnce(Response.json(demoFixture))
    .mockResolvedValue(Response.json({})));

  render(await DevelopmentPage({
    params: Promise.resolve({ matchId: "NA1_7000000001" }),
    searchParams: Promise.resolve({ focus: "6", compare: "1" }),
  }));

  expect(screen.getByLabelText("Selected interval 8:00–10:00")).toBeVisible();
});

it("keeps exact endpoints when a focal change automatically selects the lane opponent", async () => {
  vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080");
  const focalChanged = {
    ...developmentFixture,
    summary: {
      ...developmentFixture.summary,
      focusParticipantId: 7,
      compareParticipantId: null,
      kills: 5,
      deaths: 4,
      assists: 9,
      totalCs: 184,
      goldEarned: 12_500,
    },
  };
  vi.stubGlobal("fetch", vi.fn()
    .mockResolvedValueOnce(Response.json(focalChanged))
    .mockResolvedValueOnce(Response.json(demoFixture))
    .mockResolvedValue(Response.json({})));

  await expect(DevelopmentPage({
    params: Promise.resolve({ matchId: "NA1_7000000001" }),
    searchParams: Promise.resolve({ focus: "7", from: "480000", to: "600000" }),
  })).rejects.toThrow("NEXT_REDIRECT");

  expect(redirectMock).toHaveBeenCalledWith(
    "/matches/NA1_7000000001/development?focus=7&compare=2&from=480000&to=600000",
  );
});

it("preserves rune inspection through automatic comparison redirects", async () => {
  vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080");
  const data = { ...developmentFixture, summary: { ...developmentFixture.summary, focusParticipantId: 7, compareParticipantId: null } };
  vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(Response.json(data)).mockResolvedValueOnce(Response.json(demoFixture)));
  await expect(DevelopmentPage({ params: Promise.resolve({ matchId: data.matchId }), searchParams: Promise.resolve({ focus: "7", from: "480000", to: "600000", metric: "xp", finalView: "runes", runeParticipant: "6", historyRunId: "00000000-0000-0000-0000-000000000001" }) })).rejects.toThrow("NEXT_REDIRECT");
  expect(redirectMock).toHaveBeenLastCalledWith("/matches/NA1_7000000001/development?focus=7&compare=2&from=480000&to=600000&metric=xp&finalView=runes&runeParticipant=6&historyRunId=00000000-0000-0000-0000-000000000001");
});

it("restores a suggested interval after an earlier bounded endpoint has only CS", async () => {
  vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080");
  vi.stubGlobal("fetch", vi.fn()
    .mockResolvedValueOnce(Response.json(partialMetricWindowFixture))
    .mockResolvedValueOnce(Response.json(demoFixture))
    .mockResolvedValue(Response.json({})));

  render(await DevelopmentPage({
    params: Promise.resolve({ matchId: "NA1_7000000001" }),
    searchParams: Promise.resolve({ focus: "6", compare: "1", from: "0", to: "150000" }),
  }));

  expect(screen.getByLabelText("Selected interval 0:00–2:30")).toBeVisible();
  expect(screen.getByRole("table", { name: "Before and after differences" })).toHaveTextContent(
    "CS0+2Gold0+400XPUnavailableUnavailable",
  );
});


it.each([12, 14])("does not infer a lane opponent on ARAM map %i", async (mapId) => {
  vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080");
  const data = { ...developmentFixture, summary: { ...developmentFixture.summary, queueId: 450, mapId, compareParticipantId: null } };
  vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(Response.json(data))
    .mockResolvedValueOnce(Response.json(demoFixture)).mockResolvedValue(Response.json({})));
  render(await DevelopmentPage({ params: Promise.resolve({ matchId: data.matchId }), searchParams: Promise.resolve({ focus: "6" }) }));
  expect(screen.getByRole("heading", { name: "Garen progression" })).toBeVisible();
  expect(screen.getByRole("combobox", { name: "Compare with opponent" })).toHaveValue("");
});
