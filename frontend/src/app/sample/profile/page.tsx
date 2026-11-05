import type { Metadata } from "next";
import { EntryShell } from "../../../components/entry-shell";
import { SamplePlayerProfile } from "../../../components/sample-player-profile";
import { getSampleHistory } from "../../../sample/catalog";

export const metadata: Metadata = {
  title: "Northline · Sample player profile · LoL Match Analysis",
  description: "Explore a fictional Challenger player’s rank, match history, and game timelines.",
};

export default function SampleProfilePage() {
  return <EntryShell active="sample"><SamplePlayerProfile matches={getSampleHistory()} /></EntryShell>;
}
