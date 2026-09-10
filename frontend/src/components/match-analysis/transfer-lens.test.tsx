import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { lensFixtures } from "../../test/match-analysis-fixture";
import type { TransferLens as TransferLensValue } from "../../lib/analysis/types";
import { TransferLens } from "./transfer-lens";

const transfer = lensFixtures.find(
  (lens) => lens.type === "TRANSFER",
) as TransferLensValue;

it("reports only a co-occurring Transfer category delta", () => {
  render(<TransferLens lens={transfer} />);

  expect(screen.getByText(/category delta/i)).toBeVisible();
  expect(screen.getByText(/co-occurred/i)).toBeVisible();
  expect(screen.getByText("TEAM GOLD")).toBeVisible();
  expect(screen.queryByText(/caused/i)).not.toBeInTheDocument();
});
