import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { lensFixtures } from "../../test/match-analysis-fixture";
import type { MapLens as MapLensValue } from "../../lib/analysis/types";
import { MapLens } from "./map-lens";

const map = lensFixtures.find((lens) => lens.type === "MAP") as MapLensValue;

it("names an observed point without a descriptor by its recorded kind", () => {
  const lens = structuredClone(map);
  Object.assign(lens.points[0], { anchorKind: "CHAMPION_KILL", descriptor: null });
  render(<MapLens lens={lens} />);
  expect(screen.getByText("CHAMPION KILL")).toBeVisible();
});

it("shows only timestamped observed Map points", () => {
  const { container } = render(<MapLens lens={map} />);

  expect(screen.getByText("13:00")).toBeVisible();
  expect(screen.getByText("Focal participant position")).toBeVisible();
  expect(screen.getByText("5,000 × 7,000")).toBeVisible();
  expect(container.querySelector("path")).toBeNull();
});
