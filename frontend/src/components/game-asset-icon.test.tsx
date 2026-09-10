import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { GameAssetIcon } from "./game-asset-icon";

it("shows the readable fallback when an asset image fails", () => {
  render(<GameAssetIcon
    asset={{ name: "Garen", imageUrl: "https://assets.test/missing-garen.png" }}
    fallback="GA"
    className="icon"
  />);

  fireEvent.error(screen.getByRole("img", { name: "Garen" }));

  expect(screen.getByText("GA")).toHaveAttribute("aria-label", "Garen");
});

it("tries a changed asset URL after an earlier image failed", () => {
  const { rerender } = render(<GameAssetIcon
    asset={{ name: "Garen", imageUrl: "https://assets.test/missing-garen.png" }}
    fallback="GA"
    className="icon"
  />);
  fireEvent.error(screen.getByRole("img", { name: "Garen" }));

  rerender(<GameAssetIcon
    asset={{ name: "Darius", imageUrl: "https://assets.test/darius.png" }}
    fallback="DA"
    className="icon"
  />);

  expect(screen.getByRole("img", { name: "Darius" })).toHaveAttribute("src", "https://assets.test/darius.png");
  expect(screen.queryByText("DA")).toBeNull();
});
