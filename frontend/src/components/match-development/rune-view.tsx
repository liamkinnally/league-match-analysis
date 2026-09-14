"use client";

import { GameAssetIcon } from "../game-asset-icon";
import { participantName, riotId } from "../../lib/development/results";
import type { MatchDevelopment, ParticipantTotals, RuneAvailability, RuneMetric, RuneSelection, RuneStyle } from "../../lib/development/types";
import type { GameAsset, GameAssetCatalog } from "../../lib/game-assets/types";

export type RuneViewProps = { data: MatchDevelopment; assets: GameAssetCatalog | null; participantId?: number; invalidSelection?: boolean; onParticipant: (id: number) => void };
const availabilityText: Record<RuneAvailability, string> = { available: "Available", missing: "Not recorded", unsupported: "Unsupported", unverified: "Unverified" };
const metricAvailable = (metric: RuneMetric) => metric.availability === "available" && metric.value !== null && Number.isFinite(metric.value);

function selectionMetrics(selection: RuneSelection, assets: GameAssetCatalog | null): RuneMetric[] {
  return (assets?.runePerformance?.[String(selection.runeId)]?.metrics ?? []).map(definition => {
    const value = definition.variable ? selection.counters?.[`var${definition.variable}`] : null;
    const availability: RuneAvailability = definition.availability !== "available" ? "unsupported"
      : value === undefined || value === null ? "missing"
      : !Number.isSafeInteger(value) || value < 0 ? "unverified" : "available";
    return { id: definition.id, label: definition.label, availability, value: availability === "available" ? value! : null,
      unit: definition.unit === "percent" ? "%" : definition.unit, targetScope: "", timeScope: "end-of-game",
      valueBasis: "source-reported", mappingVersion: assets?.runePerformanceState?.patch ?? "unknown" };
  });
}
function RuneMetrics({ metrics, allowValues }: { metrics: RuneMetric[]; allowValues: boolean }) {
  if (!metrics.length) return <p className="rune-metric-unavailable">Performance values not available.</p>;
  return <dl className="rune-metrics">{metrics.map((metric, index) => {
    const available = allowValues && metricAvailable(metric);
    return <div key={`${metric.id}:${index}`}>
      <dt>{metric.label}</dt>
      <dd>{available ? `${metric.value!.toLocaleString("en-US", { maximumSignificantDigits: 21 })}${metric.unit ? ` ${metric.unit}` : ""}` : availabilityText[metric.availability === "available" ? "unverified" : metric.availability]}</dd>
      {metric.targetScope ? <small>{metric.targetScope}</small> : null}
    </div>;
  })}</dl>;
}
function RuneChoice({ asset, id, selected }: { asset?: GameAsset; id: number; selected: boolean }) {
  const name = asset?.name ?? `Rune ${id}`;
  return <span className="rune-choice" data-selected={selected} role="img" aria-label={`${selected ? "Selected" : "Not selected"} ${name}`} title={`${name}${selected ? " — Selected" : ""}`}>
    <GameAssetIcon asset={asset} fallback="?" className="rune-choice-image" />
  </span>;
}
function SelectedRune({ selection, assets, allowValues }: { selection: RuneSelection; assets: GameAssetCatalog | null; allowValues: boolean }) {
  const asset = assets?.runes?.[String(selection.runeId)];
  return <div className="rune-selected-copy">
    <strong>{asset?.name ?? assets?.runePerformance?.[String(selection.runeId)]?.name ?? `Rune ${selection.runeId}`}</strong>
    <p className="rune-description">{asset?.description ?? "Description unavailable for this patch."}</p>
    <RuneMetrics metrics={selectionMetrics(selection, assets)} allowValues={allowValues} />
  </div>;
}
function RuneTree({ style, assets, allowValues }: { style: RuneStyle; assets: GameAssetCatalog | null; allowValues: boolean }) {
  const tree = style.styleId === null ? undefined : assets?.runeTrees?.[String(style.styleId)];
  const secondary = style.role === "subStyle";
  const label = secondary ? "Secondary runes" : style.role === "primaryStyle" ? "Primary runes" : "Recorded runes";
  const rows = tree && style.role !== "unknown" ? tree.slots.slice(secondary ? 1 : 0) : [];
  const represented = new Set(rows.flat());
  const remaining = style.selections.filter(selection => !represented.has(selection.runeId));
  return <section className="rune-tree" aria-label={label}>
    <header><GameAssetIcon asset={tree} fallback="?" className="rune-tree-image" /><div><span>{label}</span><h3>{tree?.name ?? (style.styleId === null ? "Tree not recorded" : `Tree ${style.styleId}`)}</h3></div></header>
    {rows.map((choices, row) => {
      const selections = style.selections.filter(selection => choices.includes(selection.runeId));
      return <div className="rune-tree-row" key={row}>
        <div className="rune-choices">{choices.map(id => <RuneChoice key={id} id={id} asset={assets?.runes?.[String(id)]} selected={selections.some(selection => selection.runeId === id)} />)}</div>
        {selections.length ? selections.map((selection, index) => <SelectedRune key={`${selection.runeId}:${index}`} selection={selection} assets={assets} allowValues={allowValues} />) : <span className="rune-no-selection">{secondary ? "No selection in this row" : "Selection not recorded"}</span>}
      </div>;
    })}
    {remaining.map((selection, index) => <div className="rune-tree-row rune-recorded-selection" key={`${selection.runeId}:${index}`}>
      <RuneChoice id={selection.runeId} asset={assets?.runes?.[String(selection.runeId)]} selected />
      <SelectedRune selection={selection} assets={assets} allowValues={allowValues} />
      {tree ? <small>Recorded selection outside supported tree rows.</small> : null}
    </div>)}
    {!style.selections.length ? <p>Rune selections not recorded.</p> : null}
  </section>;
}
const totalsLabels: Record<keyof ParticipantTotals, string> = {
  totalDamageDealt: "Damage dealt to all targets", totalDamageDealtToChampions: "Damage dealt to champions",
  totalHeal: "Total healing", totalHealsOnTeammates: "Healing on teammates", totalDamageShieldedOnTeammates: "Damage shielded on teammates",
};
function MatchTotals({ totals }: { totals?: ParticipantTotals | null }) {
  const recorded = totals ? Object.entries(totals).filter((entry): entry is [keyof ParticipantTotals, number] => typeof entry[1] === "number" && Number.isFinite(entry[1])) : [];
  if (!recorded.length) return null;
  return <details className="rune-match-totals"><summary>Match totals context</summary><p>Participant-wide end-of-game totals. These values are not attributed to runes.</p><dl>{recorded.map(([key, value]) => <div key={key}><dt>{totalsLabels[key]}</dt><dd>{value.toLocaleString("en-US")}</dd></div>)}</dl></details>;
}
export function RuneView({ data, assets, participantId, invalidSelection, onParticipant }: RuneViewProps) {
  const roster = [...data.roster].sort((a, b) => a.teamId - b.teamId || a.participantId - b.participantId);
  const requested = roster.find(person => person.participantId === participantId);
  const selected = requested ?? roster.find(person => person.participantId === data.summary.focusParticipantId) ?? roster[0];
  if (!selected) return <p>Participant roster unavailable.</p>;
  const runes = selected.runes;
  const metadataMatches = !!runes?.layoutManifestId && assets?.manifest?.scope === "match-patch"
    && runes.layoutManifestId === assets.manifest.id
    && runes.matchPatch === data.summary.gameVersion.split(".").slice(0, 2).join(".")
    && runes.matchPatch === assets.assetVersion.split(".").slice(0, 2).join(".");
  const patch = data.summary.gameVersion.split(".").slice(0, 2).join(".");
  const performanceState = assets?.runePerformanceState;
  const allowValues = runes?.matchPatch === patch && performanceState?.patch === patch
    && (performanceState.status === "available" || performanceState.status === "stale");
  const runeAssets = assets ? { ...assets,
    ...(metadataMatches ? {} : { runes: allowValues ? assets.runes : undefined, runeTrees: undefined, statShards: undefined, statShardSlots: undefined }),
    runePerformance: allowValues ? assets.runePerformance : undefined,
  } : null;
  const selections = runes?.styles.flatMap(style => style.selections) ?? [];
  const availableCount = allowValues ? selections.filter(selection => selectionMetrics(selection, runeAssets).some(metricAvailable)).length : 0;
  const identity = `${riotId(selected)} · ${participantName(selected, assets)}`;
  return <div className="rune-view">
    <div className="rune-portrait-scroll">
      <div className="rune-portraits" role="group" aria-label="Inspect participant runes">{[...new Set(roster.map(person => person.teamId))].map(teamId =>
        <div className="rune-portrait-team" key={teamId}>{roster.filter(person => person.teamId === teamId).map(person => {
          const name = `${riotId(person)} · ${participantName(person, assets)} · Team ${person.teamId} · participant ${person.participantId}`;
          return <button key={person.participantId} type="button" aria-label={name} title={name} aria-pressed={selected.participantId === person.participantId}
            onClick={() => onParticipant(person.participantId)}>
            <GameAssetIcon asset={assets?.champions[String(person.championId)]} fallback={person.championName.slice(0, 2)} className="rune-portrait" />
          </button>;
        })}</div>
      )}</div>
    </div>
    <header className="rune-identity"><h2>{identity}</h2><p><span>End-of-game rune stats</span> · Patch {data.summary.gameVersion.split(".").slice(0, 2).join(".")}</p></header>
    <p className="rune-announcement" role="status" aria-live="polite">Showing runes for {identity}.</p>
    {invalidSelection || (participantId !== undefined && !requested) ? <p className="rune-availability">Requested rune participant unavailable. Showing the analysis participant.</p> : null}
    {!runes || (!selections.length && runes.availability === "missing") ? <p className="rune-availability">Rune selections not recorded for this participant.</p> : <>
      {!metadataMatches ? <p className="rune-availability">Patch-matched rune layout unavailable. Recorded selections are listed below.</p> : null}
      {runes.availability !== "available" ? <p className="rune-availability">Rune selection status: {availabilityText[runes.availability].toLowerCase()}.</p> : null}
      {allowValues && performanceState?.status === "stale" ? <p className="rune-availability">Using cached rune descriptions for patch {patch}{performanceState.retrievedAt ? `, retrieved ${performanceState.retrievedAt}` : ""}. Refresh is temporarily unavailable. <a href={performanceState.sourceUrl} target="_blank" rel="noreferrer">Description source</a></p> : null}
      {!allowValues ? <p className="rune-availability">Patch-matched rune descriptions unavailable. Performance values cannot be labeled yet.</p> : null}
      <div className="rune-trees">{runes.styles.map((style, index) => <RuneTree key={`${style.styleId}:${index}`} style={style} assets={runeAssets} allowValues={!!allowValues} />)}</div>
      <section className="rune-shards" aria-label="Stat shards"><h3>Stat shards</h3><div>{(["offense", "flex", "defense"] as const).map((slot, index) => {
        const id = runes.shards[slot], validChoices = runeAssets?.statShardSlots?.[index] ?? [];
        const choices = id !== null && !validChoices.includes(id) ? [...validChoices, id] : validChoices;
        const asset = id === null ? undefined : runeAssets?.statShards?.[String(id)];
        return <div className="rune-shard-row" key={slot}><strong>{slot[0].toUpperCase() + slot.slice(1)}</strong><div className="rune-choices">{choices.map(choice => <RuneChoice key={choice} id={choice} asset={runeAssets?.statShards?.[String(choice)]} selected={choice === id} />)}</div><span>{asset?.name ?? (id === null ? "Not recorded" : `Shard ${id}`)}</span></div>;
      })}</div></section>
      <p className="rune-coverage">Values available for {availableCount} of {selections.length} selected runes.</p>
    </>}
    <MatchTotals totals={selected.participantTotals} />
  </div>;
}
