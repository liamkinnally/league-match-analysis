import { render, screen, within } from "@testing-library/react";
import { expect, it } from "vitest";
import { sequenceLens } from "../../test/match-analysis-fixture";
import type { SequenceLens as SequenceLensValue } from "../../lib/analysis/types";
import { SequenceLens } from "./sequence-lens";

const sequence = sequenceLens as SequenceLensValue;

it("names a descriptor-free sequence anchor without inventing event details", () => {
  const lens = structuredClone(sequence);
  Object.assign(lens.bands[0].anchors[0], { kind: "CHAMPION_KILL", descriptor: null });
  render(<SequenceLens lens={lens} />);
  expect(screen.getByText("CHAMPION KILL")).toBeVisible();
});

it("keeps same-time Sequence observations visibly parallel", () => {
  render(
    <SequenceLens
      lens={{
        ...sequence,
        bands: [
          {
            ...sequence.bands[0],
            parallel: true,
            anchors: [
              ...sequence.bands[0].anchors,
              {
                ...sequence.bands[0].anchors[0],
                descriptor: "Opponent objective trade",
              },
            ],
          },
        ],
      }}
    />,
  );

  const band = screen.getByTestId("sequence-band");
  expect(band).toHaveAttribute("data-parallel", "true");
  expect(within(band).getAllByRole("listitem")).toHaveLength(2);
  expect(screen.getByText(/parallel observations/i)).toBeVisible();
});
