import { render, screen, within } from "@testing-library/react";
import { expect, it } from "vitest";
import { lensFixtures } from "../../test/match-analysis-fixture";
import type { StateLens as StateLensValue } from "../../lib/analysis/types";
import { StateLens } from "./state-lens";

const state = lensFixtures.find((lens) => lens.type === "STATE") as StateLensValue;

it("preserves represented sample time and consequence semantics", () => {
  render(<StateLens lens={state} />);

  const receipt = screen.getByTestId("state-receipt");
  expect(within(receipt).getByText("13:00")).toBeVisible();
  expect(within(receipt).getByText("15:00")).toBeVisible();
  expect(within(receipt).getByText(/antecedent evidence/i)).toBeVisible();
  expect(within(receipt).getByText(/consequence evidence/i)).toBeVisible();
});
