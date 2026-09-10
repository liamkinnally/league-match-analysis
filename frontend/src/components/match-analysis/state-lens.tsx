import type { StateLens as StateLensValue } from "../../lib/analysis/types";
import { StateReceipt } from "./state-receipt";

type Props = {
  lens: StateLensValue;
};

export function StateLens({ lens }: Props) {
  return <StateReceipt receipt={lens.receipt} />;
}
