import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
import {
  developmentFixture,
  partialMetricWindowFixture,
} from "../../test/match-development-fixture";
import type { MatchDevelopment } from "../../lib/development/types";
import { MatchDevelopmentView } from "./match-development-view";

const push = vi.fn();
vi.mock("next/navigation", async () => {
  const { useSyncExternalStore } = await import("react");
  return {
    useRouter: () => ({ push }),
    useSearchParams: () =>
      new URLSearchParams(
        useSyncExternalStore(
          (callback) => {
            window.addEventListener("popstate", callback);
            return () => window.removeEventListener("popstate", callback);
          },
          () => window.location.search,
          () => "",
        ),
      ),
  };
});
const nativePush = window.history.pushState.bind(window.history);
beforeEach(() => {
  window.history.replaceState(null, "", "/");
  vi.spyOn(window.history, "pushState").mockImplementation((...args) => {
    nativePush(...args);
    window.dispatchEvent(new PopStateEvent("popstate"));
  });
  push.mockClear();
});
afterEach(() => vi.restoreAllMocks());
const openEvents = () =>
  fireEvent.click(screen.getByText("Events in interval"));

const assets = {
  assetVersion: "16.17.1",
  champions: {
    "61": { name: "Orianna", imageUrl: "https://assets.test/Orianna.png" },
    "64": { name: "Lee Sin", imageUrl: "https://assets.test/LeeSin.png" },
    "86": { name: "Garen", imageUrl: "https://assets.test/Garen.png" },
    "122": { name: "Darius", imageUrl: "https://assets.test/Darius.png" },
    "254": { name: "Vi", imageUrl: "https://assets.test/Vi.png" },
  },
  items: {
    "3071": { name: "Black Cleaver", imageUrl: "https://assets.test/3071.png" },
    "3364": { name: "Oracle Lens", imageUrl: "https://assets.test/3364.png" },
  },
  spells: {},
};

it("keeps result styling independent of team side and roster order", () => {
  render(
    <MatchDevelopmentView
      data={{
        ...developmentFixture,
        roster: [...developmentFixture.roster].reverse(),
      }}
      interval={{ from: 480000, to: 600000 }}
      invented
      assets={assets}
    />,
  );
  const red = screen
      .getByRole("heading", { name: "Red team" })
      .closest("section")!,
    blue = screen
      .getByRole("heading", { name: "Blue team" })
      .closest("section")!;
  expect(red).toHaveClass("match-result--victory");
  expect(blue).toHaveClass("match-result--defeat");
  expect(within(red).getByText("Victory")).toBeVisible();
  expect(within(blue).getByText("Defeat")).toBeVisible();
});
it.each([undefined, null, 0, 1, "false"])(
  "keeps unknown result %s neutral",
  (win) => {
    const data = {
      ...developmentFixture,
      summary: { ...developmentFixture.summary, win },
      roster: developmentFixture.roster.map((p) => ({ ...p, win })),
    } as unknown as MatchDevelopment;
    render(
      <MatchDevelopmentView
        data={data}
        interval={{ from: 480000, to: 600000 }}
        invented
        assets={assets}
      />,
    );
    expect(screen.queryByText("Victory")).toBeNull();
    expect(screen.queryByText("Defeat")).toBeNull();
    for (const node of screen.getAllByText("Result unavailable"))
      expect(node.closest(".match-result")).toHaveClass(
        "match-result--unknown",
      );
  },
);
it("preserves inventory data, compacts occupied slots, and leaves the trinket fixed", () => {
  const data = structuredClone(developmentFixture);
  data.roster[1].endItemIds = [0, 3071, 6630, 3111, 3053, 3071, 3364];
  const source = [...data.roster[1].endItemIds];
  render(
    <MatchDevelopmentView
      data={data}
      interval={{ from: 480000, to: 600000 }}
      invented
      assets={assets}
    />,
  );
  const row = screen.getByRole("row", { name: /Lee Sin/ });
  for (const inventory of within(row).getAllByLabelText("Final items")) {
    expect(inventory.children).toHaveLength(7);
    expect(
      [...inventory.children].map((n) => n.getAttribute("data-original-slot")),
    ).toEqual(["1", "2", "3", "4", "5", "0", "6"]);
    expect(inventory.children[5]).toHaveAttribute("title", "Empty item slot 6");
    expect(inventory.children[6]).toHaveClass("development-item-slot--trinket");
    expect(
      within(inventory.children[6] as HTMLElement).getByRole("button", {
        name: "Oracle Lens",
      }),
    ).toBeInTheDocument();
  }
  expect(data.roster[1].endItemIds).toEqual(source);
});
it("starts events collapsed and expands every event with precise records and explicit kill roles", () => {
  const data = structuredClone(developmentFixture) as MatchDevelopment;
  data.roster.push({
    ...data.roster[3],
    participantId: 8,
    championId: 61,
    championName: "Orianna",
    teamPosition: "MIDDLE",
  });
  data.events[1] = {
    ...data.events[1],
    assisterParticipantIds: [7, 8],
    assistersObserved: true,
  };
  render(
    <MatchDevelopmentView
      data={data}
      interval={{ from: 480000, to: 600000 }}
      invented
      assets={assets}
    />,
  );
  const details = screen.getByText("Events in interval").closest("details")!;
  expect(details.open).toBe(false);
  expect(screen.getByText("3 events")).toBeVisible();
  openEvents();
  expect(details.open).toBe(true);
  expect(details.querySelectorAll("li")).toHaveLength(3);
  expect(within(details).getByText("Purchased Black Cleaver")).toBeVisible();
  const kill = within(details).getByText("Killed Darius").closest("li")!;
  expect(kill).toHaveTextContent("Garen");
  expect(kill.querySelector(".development-event-assists")).toHaveTextContent(
    "Assists:Vi,Orianna",
  );
  expect(within(kill).getByRole("img", { name: "Darius, killed" })).toHaveClass(
    "refined-event-victim",
  );
  expect(
    kill.querySelector('[data-event-symbol="CHAMPION_KILL"]'),
  ).not.toBeNull();
  fireEvent.click(within(kill).getByText("Record details"));
  expect(kill.querySelector("pre")).toHaveTextContent("552430");
});
it("omits assisters when the source role is unavailable", () => {
  const data = structuredClone(developmentFixture);
  data.events[1] = {
    ...data.events[1],
    assisterParticipantIds: [],
    assistersObserved: false,
  };
  render(
    <MatchDevelopmentView
      data={data}
      interval={{ from: 480000, to: 600000 }}
      invented
      assets={assets}
    />,
  );
  openEvents();
  expect(
    screen
      .getByText("Killed Darius")
      .closest("li")!
      .querySelector(".development-event-assists"),
  ).toBeNull();
});
it("does not infer unknown actors from another participant listed in a record", () => {
  const data = structuredClone(developmentFixture);
  data.events = [
    { ...data.events[0], actorParticipantId: 999, participantIds: [6] },
  ];
  render(
    <MatchDevelopmentView
      data={data}
      interval={{ from: 480000, to: 600000 }}
      invented
      assets={assets}
    />,
  );
  openEvents();
  const event = screen.getByText("Purchased Black Cleaver").closest("li")!;
  expect(event).toHaveTextContent("Actor unavailable");
  expect(within(event).queryByRole("img", { name: "Garen" })).toBeNull();
});
it("keeps unresolved item codes in expandable details, with readable identity", () => {
  render(
    <MatchDevelopmentView
      data={developmentFixture}
      interval={{ from: 480000, to: 600000 }}
      invented
      assets={null}
    />,
  );
  openEvents();
  const event = screen.getByText("Purchased Unresolved item").closest("li")!;
  expect(event).toHaveTextContent("Garen");
  expect(within(event).queryByText("Purchased item 3071")).toBeNull();
  fireEvent.click(within(event).getByText("Record details"));
  expect(event.querySelector("pre")).toHaveTextContent('"itemId": 3071');
});
it("switches metrics in URL, endpoints and samples without filling missing observations", () => {
  render(
    <MatchDevelopmentView
      data={developmentFixture}
      interval={{ from: 480000, to: 600000 }}
      invented
      assets={assets}
    />,
  );
  expect(
    screen.getByRole("heading", { name: "Gold difference over time" }),
  ).toBeVisible();
  expect(
    screen.getByText("Above 0: Garen leads in gold. Below 0: Darius leads."),
  ).toBeVisible();
  fireEvent.click(screen.getByText("Show all 3 sampled values"));
  const table = screen.getByRole("table", { name: "All sampled gold values" });
  expect(within(table).getByText("Unavailable")).toBeVisible();
  expect(within(table).queryByText("0 g")).toBeNull();
  fireEvent.click(screen.getByRole("tab", { name: "CS" }));
  expect(window.location.search).toBe("?metric=cs");
  expect(screen.getAllByText("+4 CS").length).toBeGreaterThan(0);
  expect(
    screen.getByRole("table", { name: "All sampled cs values" }),
  ).toHaveTextContent("Unavailable");
});
it("keeps suggested links and all before/after metrics consistent", () => {
  render(
    <MatchDevelopmentView
      data={developmentFixture}
      interval={{ from: 480000, to: 600000 }}
      invented
      assets={assets}
    />,
  );
  expect(
    screen.getByRole("link", { name: /8:00–10:00 Garen/ }),
  ).toHaveAttribute(
    "href",
    "/matches/NA1_7000000001/development?focus=6&compare=1&from=480000&to=600000",
  );
  expect(
    screen.getByRole("table", { name: "Before and after differences" }),
  ).toHaveTextContent("CS+4+13Gold+100+510XP+20+220");
});
it("keeps charts and samples available without suggestions; other intervals remain disclosed", () => {
  render(
    <MatchDevelopmentView
      data={{ ...developmentFixture, suggestedWindows: [] }}
      interval={{ from: 480000, to: 600000 }}
      invented
      assets={assets}
    />,
  );
  expect(
    screen.getByText("No suggested windows met the gold-change threshold."),
  ).toBeVisible();
  const details = screen
    .getByText("Other recorded intervals")
    .closest("details")!;
  expect(details.open).toBe(false);
  fireEvent.click(screen.getByText("Other recorded intervals"));
  expect(details.querySelectorAll("tbody tr")).toHaveLength(1);
});
it("shows unavailable metrics in the before/after table", () => {
  const data = {
    ...developmentFixture,
    windows: [
      {
        ...developmentFixture.windows[0],
        before: { ...developmentFixture.windows[0].before, xpDifference: null },
        after: { ...developmentFixture.windows[0].after, xpDifference: null },
      },
    ],
  };
  render(
    <MatchDevelopmentView
      data={data}
      interval={{ from: 480000, to: 600000 }}
      invented
      assets={assets}
    />,
  );
  expect(
    screen.getByRole("table", { name: "Before and after differences" }),
  ).toHaveTextContent("XPUnavailableUnavailable");
});
it("offers one opponent selector and preserves exact interval and metric", () => {
  const data = {
    ...developmentFixture,
    summary: { ...developmentFixture.summary, compareParticipantId: null },
  };
  render(
    <MatchDevelopmentView
      data={data}
      interval={{ from: 480000, to: 600000 }}
      invented
      assets={assets}
    />,
  );
  expect(
    screen.getByRole("heading", { name: "Gold earned over time" }),
  ).toBeVisible();
  const selector = screen.getByRole("combobox", {
    name: "Compare with opponent",
  });
  expect(selector).toHaveValue("");
  expect(
    within(selector).getByRole("option", { name: "Darius — TOP" }),
  ).toBeInTheDocument();
  fireEvent.click(screen.getByRole("tab", { name: "XP" }));
  fireEvent.change(selector, { target: { value: "1" } });
  expect(push).toHaveBeenCalledWith(
    "/matches/NA1_7000000001/development?focus=6&compare=1&from=480000&to=600000&metric=xp",
    { scroll: false },
  );
});
it("renders partial-metric windows at their recorded bounds", () => {
  render(
    <MatchDevelopmentView
      data={partialMetricWindowFixture}
      interval={{ from: 0, to: 150000 }}
      invented
      assets={assets}
    />,
  );
  expect(
    screen.getByRole("table", { name: "Before and after differences" }),
  ).toHaveTextContent("CS0+2Gold0+400XPUnavailableUnavailable");
  expect(screen.getByText("From: 0:00")).toBeVisible();
  expect(screen.getByText("To: 2:30")).toBeVisible();
});
it("supports keyboard metric tabs", () => {
  render(
    <MatchDevelopmentView
      data={developmentFixture}
      interval={{ from: 480000, to: 600000 }}
      invented
      assets={assets}
    />,
  );
  const gold = screen.getByRole("tab", { name: "Gold" });
  gold.focus();
  fireEvent.keyDown(gold, { key: "ArrowRight" });
  expect(screen.getByRole("tab", { name: "CS" })).toHaveFocus();
  expect(screen.getByRole("tab", { name: "CS" })).toHaveAttribute(
    "aria-selected",
    "true",
  );
});
it("shows original-language names and secondary tags, without visible selection instructions", () => {
  const data = structuredClone(developmentFixture) as MatchDevelopment;
  data.roster[2] = {
    ...data.roster[2],
    gameName: "안녕하세요",
    tagLine: "测试",
    teamPosition: "UTILITY",
  };
  render(
    <MatchDevelopmentView
      data={data}
      interval={{ from: 480000, to: 600000 }}
      invented
      assets={assets}
    />,
  );
  expect(screen.getByText("안녕하세요")).toBeVisible();
  expect(screen.getByText("#测试")).toHaveClass("refined-summoner-tag");
  expect(screen.getByTitle("안녕하세요#测试")).toHaveAttribute(
    "href",
    expect.stringContaining("focus=6"),
  );
  expect(screen.queryByText(/Focus on/)).toBeNull();
  expect(screen.queryByText("UTILITY")).toBeNull();
  expect(screen.getByText("Ranked Solo/Duo")).toHaveClass("refined-queue");
  expect(screen.getByText("Current avg. tier")).toBeVisible();
});
it("keeps final results available when the timeline is absent", () => {
  render(
    <MatchDevelopmentView
      data={{
        ...developmentFixture,
        timelineAvailable: false,
        samples: [],
        windows: [],
        suggestedWindows: [],
        events: [],
      }}
      interval={{ from: 0, to: 1800000 }}
      invented
      assets={null}
    />,
  );
  expect(
    screen.getByRole("heading", { name: "Timeline unavailable" }),
  ).toBeVisible();
  expect(
    screen.getByRole("heading", { name: "Final team results" }),
  ).toBeVisible();
  expect(screen.getByRole("heading", { name: "Scoreboard" })).toBeVisible();
});
it("keeps missing final totals distinct from verified zero with compact accessible labels", () => {
  render(
    <MatchDevelopmentView
      data={{ ...developmentFixture, teams: [{
        teamId: 100, win: false, kills: null, deaths: null, assists: null,
        goldEarned: null, objectives: { tower: 0, dragon: null },
      }] }}
      interval={{ from: 480000, to: 600000 }} invented assets={null}
    />,
  );
  const table = screen.getByRole("table", { name: "Final team results" });
  expect(within(table).getAllByLabelText("Unavailable").length).toBeGreaterThan(0);
  expect(within(table).getByRole("cell", { name: "0" })).toBeVisible();
  expect(table.textContent).not.toContain("Unavailable / Unavailable");
});
it("keeps captured events accessible when usable participant samples are absent", () => {
  render(
    <MatchDevelopmentView
      data={{
        ...developmentFixture,
        samples: [],
        windows: [],
        suggestedWindows: [],
      }}
      interval={{ from: 0, to: 1800000 }}
      invented
      assets={assets}
    />,
  );
  expect(
    screen.getByRole("heading", { name: "Timeline samples unavailable" }),
  ).toBeVisible();
  openEvents();
  expect(screen.getByText("Purchased Black Cleaver")).toBeVisible();
  expect(
    screen
      .getByText("Events in interval")
      .closest("details")!
      .querySelectorAll("li"),
  ).toHaveLength(3);
});
