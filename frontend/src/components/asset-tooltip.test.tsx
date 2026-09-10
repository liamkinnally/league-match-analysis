import { act, fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { AssetTooltip } from "./asset-tooltip";

it("opens item prices by keyboard and tap, dismisses on Escape, and does not select its row", () => {
  const row = vi.fn();
  render(
    <div onClick={row}>
      <AssetTooltip
        kind="item"
        asset={{
          name: "Black Cleaver",
          imageUrl: "https://assets.test/3071.png",
          gold: { total: 3000, base: 700, sell: 2100, purchasable: true },
        }}
        patch="16.17.1"
        fallback="?"
        iconClass="icon"
      />
    </div>,
  );
  const trigger = screen.getByRole("button", { name: "Black Cleaver" });
  fireEvent.focus(trigger);
  expect(screen.getByRole("tooltip")).toHaveTextContent(
    "Total cost3,000 goldCombine cost700 goldSell value2,100 gold",
  );
  fireEvent.keyDown(document, { key: "Escape" });
  expect(screen.queryByRole("tooltip")).toBeNull();
  fireEvent.click(trigger);
  expect(screen.getByRole("tooltip")).toBeVisible();
  fireEvent.click(screen.getByRole("tooltip"));
  expect(row).not.toHaveBeenCalled();
});
it("shows summoner spell names, description and base cooldown without inventing prices", () => {
  render(
    <AssetTooltip
      kind="spell"
      asset={{
        name: "Flash",
        imageUrl: "https://assets.test/flash.png",
        description: "Teleports a short distance.",
        cooldown: [300],
      }}
      patch="16.17.1"
      fallback="?"
      iconClass="icon"
    />,
  );
  fireEvent.focus(screen.getByRole("button", { name: "Flash" }));
  expect(screen.getByRole("tooltip")).toHaveTextContent("Base cooldown300s");
  expect(screen.queryByText("Total cost")).toBeNull();
});
it("allows touching and scrolling tooltip content without dismissing it", () => {
  render(
    <AssetTooltip
      kind="spell"
      asset={{
        name: "Flash",
        imageUrl: "https://assets.test/flash.png",
        description: "Teleports a short distance.",
        cooldown: [300],
      }}
      fallback="?"
      iconClass="icon"
    />,
  );
  fireEvent.click(screen.getByRole("button", { name: "Flash" }));
  const popup = screen.getByRole("tooltip");
  fireEvent.pointerDown(popup);
  fireEvent.scroll(popup);
  expect(screen.getByRole("tooltip")).toBeVisible();
  fireEvent.pointerDown(document.body);
  expect(screen.queryByRole("tooltip")).toBeNull();
});
it("keeps a keyboard-focused tooltip open when the browser scrolls its trigger into view", () => {
  render(
    <AssetTooltip kind="item" asset={{ name: "Black Cleaver", imageUrl: "https://assets.test/3071.png" }} fallback="?" iconClass="icon" />,
  );
  act(() => screen.getByRole("button", { name: "Black Cleaver" }).focus());
  fireEvent.scroll(document);
  fireEvent.scroll(window);
  fireEvent.resize(window);
  expect(screen.getByRole("tooltip")).toBeVisible();
  fireEvent.keyDown(document, { key: "Escape" });
  expect(screen.queryByRole("tooltip")).toBeNull();
});
