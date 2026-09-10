import { RouteUnavailable } from "../components/route-state";
export default function NotFound() {
  return <RouteUnavailable title="Page not found" description="This address doesn’t lead to a page. Search for a player to open a recent match." />;
}
