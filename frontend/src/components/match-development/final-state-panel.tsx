import type { ReactNode, KeyboardEvent } from "react";
import { RuneView } from "./rune-view";
import type { MatchDevelopment } from "../../lib/development/types";
import type { FinalStateSelection } from "../../lib/development/route";
import type { GameAssetCatalog } from "../../lib/game-assets/types";

export function FinalStatePanel({ data, assets, view, participantId, invalidSelection, onChange, children }: {
  data: MatchDevelopment; assets: GameAssetCatalog | null; view: "scoreboard" | "runes";
  participantId?: number; invalidSelection: boolean; onChange: (state: FinalStateSelection) => void; children: ReactNode;
}) {
  const keyboardTab = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const key = event.key;
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(key)) return;
    event.preventDefault();
    const next = key === "Home" ? 0 : key === "End" ? 1 : 1 - index;
    event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>("[role=tab]")[next]?.focus();
    onChange({ finalView: next ? "runes" : "scoreboard" });
  };
  return <section className="development-final-state" aria-label="Final state">
    <div className="final-state-tabs" role="tablist" aria-label="Final state view">
      {(["scoreboard", "runes"] as const).map((tab, index) => <button key={tab} type="button" id={`final-${tab}-tab`} role="tab" aria-selected={view === tab} aria-controls={`final-${tab}-panel`} tabIndex={view === tab ? 0 : -1}
        onClick={() => onChange({ finalView: tab })} onKeyDown={event => keyboardTab(event, index)}>{tab === "scoreboard" ? "Scoreboard" : "Runes"}</button>)}
    </div>
    <div id="final-scoreboard-panel" role="tabpanel" aria-labelledby="final-scoreboard-tab" hidden={view !== "scoreboard"}>{children}</div>
    <div id="final-runes-panel" role="tabpanel" aria-labelledby="final-runes-tab" hidden={view !== "runes"}>
      {view === "runes" ? <RuneView data={data} assets={assets} participantId={participantId} invalidSelection={invalidSelection} onParticipant={id => onChange({ finalView: "runes", runeParticipant: id })} /> : null}
    </div>
  </section>;
}
