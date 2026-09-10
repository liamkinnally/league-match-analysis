import type {
  ActiveAnalysis,
  AnalysisRouteContext,
} from "../../lib/analysis/types";
import { Investigation } from "./investigation";

type Props = {
  active: ActiveAnalysis;
  route: AnalysisRouteContext;
};

export function TransitionStage({ active, route }: Props) {
  return <Investigation active={active} route={route} />;
}
