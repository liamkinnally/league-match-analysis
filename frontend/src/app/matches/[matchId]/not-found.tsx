import { RouteUnavailable } from "../../../components/route-state";

export default function MatchNotFound() {
  return <RouteUnavailable title="Match not found" description="This match or selected player is unavailable. Check the link or find another recent match." />;
}
