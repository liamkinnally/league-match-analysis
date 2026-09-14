import { useMemo, useState } from "react";
import { GameAssetIcon } from "../game-asset-icon";
import { AssetTooltip } from "../asset-tooltip";
import { eventDisplayRows, type DisplayEvent, type EventRelation } from "../../lib/development/events";
import { resolveAbilityAsset, resolveEventAsset } from "../../lib/game-assets/event-assets";
import { timeLabel } from "../../lib/development/chart-model";
import { participantName } from "../../lib/development/results";
import type {
  DevelopmentInterval,
  MatchDevelopment,
  MatchDevelopmentParticipant,
} from "../../lib/development/types";
import type { GameAsset, GameAssetCatalog } from "../../lib/game-assets/types";

function EventChampion({
  person,
  assets,
  killed = false,
}: {
  person: MatchDevelopmentParticipant;
  assets: GameAssetCatalog | null;
  killed?: boolean;
}) {
  const asset = assets?.champions[String(person.championId)],
    name = participantName(person, assets);
  return (
    <GameAssetIcon
      asset={
        asset
          ? { ...asset, name: killed ? `${name}, killed` : name }
          : undefined
      }
      fallback={name.slice(0, 2)}
      className={`development-event-champion${killed ? " refined-event-victim" : ""}`}
    />
  );
}
const relationColor: Record<EventRelation, string> = { ally: "#79b5ef", enemy: "#e29191", unknown: "#a0a29b" };

function WardGlyph({ asset, relation }: { asset?: GameAsset; relation: EventRelation }) {
  const [failed, setFailed] = useState<string | null>(null);
  const url = asset?.imageUrl;
  if (url && failed !== url) return (
    <span className="event-ward-glyph" data-ward-glyph data-relation={relation}
      style={{ backgroundColor: relationColor[relation], maskImage: `url("${url}")`, WebkitMaskImage: `url("${url}")` }}>
      {/* The image reports failures; the provider mask colors the glyph itself. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={url} alt="" className="event-mask-probe" onError={() => setFailed(url)} />
    </span>
  );
  return <svg data-ward-glyph data-relation={relation} className="event-ward-glyph" viewBox="0 0 20 20" fill="none" style={{ color: relationColor[relation] }}>
    <path d="M2 10s3-5 8-5 8 5 8 5-3 5-8 5-8-5-8-5Z" stroke="currentColor" strokeWidth="1.5" />
    <circle cx="10" cy="10" r="2.5" fill="currentColor" />
  </svg>;
}

function EventSymbol({ row, assets }: { row: DisplayEvent; assets: GameAssetCatalog | null }) {
  const type = row.type;
  if (type === "WARD_PLACED") return <span className="refined-event-symbol event-action-symbol" data-event-symbol={type} aria-hidden="true"><WardGlyph asset={resolveEventAsset(assets, "WARD_EYE")} relation={row.actorRelation} /></span>;
  const paths: Record<string, string> = {
    ITEM_PURCHASED: "M10 3v14M3 10h14",
    ITEM_SOLD: "M3 3v14h9M7 10h11m-4-4 4 4-4 4",
    ITEM_DESTROYED: "M4 10h12",
    ITEM_UNDO: "m7 3-4 4 4 4M3 7h8a5 5 0 0 1 0 10H8",
    WARD_KILL: "m5 5 10 10M15 5 5 15",
    BUILDING_KILL: "m5 5 10 10M15 5 5 15",
    TURRET_PLATE_DESTROYED: "m5 5 10 10M15 5 5 15",
    SKILL_LEVEL_UP: "M4 4h12v12H4zM10 7v6M7 10h6",
    LEVEL_UP: "m4 13 6-6 6 6M4 8l6-6 6 6",
    CHAMPION_KILL: "m4 16 12-12M10 4h6v6M3 12l5 5",
    CHAMPION_SPECIAL_KILL: "m4 16 12-12M10 4h6v6M3 12l5 5",
    ELITE_MONSTER_KILL: "m3 10 5 5L17 5",
    GAME_END: "M5 3v15M5 4h11l-3 4 3 4H5",
    PAUSE_END: "m6 3 10 7-10 7Z",
  };
  return <svg className="refined-event-symbol event-action-symbol" data-event-symbol={type} viewBox="0 0 20 20" fill="none" aria-hidden="true">
    <path d={paths[type] ?? "M10 3v8M10 15v2"} stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
  </svg>;
}
export function EventFeed({
  data,
  interval,
  assets,
}: {
  data: MatchDevelopment;
  interval: DevelopmentInterval;
  assets: GameAssetCatalog | null;
}) {
  const allRows = useMemo(
    () => eventDisplayRows(data.events, data.roster, assets, data.summary.focusParticipantId),
    [data.events, data.roster, assets, data.summary.focusParticipantId],
  );
  const rows = allRows.filter(
    (e) => e.timestampMs >= interval.from && e.timestampMs <= interval.to,
  );
  const secondCounts = new Map<number, number>();
  for (const row of rows) {
    const second = Math.floor(row.timestampMs / 1000);
    secondCounts.set(second, (secondCounts.get(second) ?? 0) + 1);
  }
  const recordCount = rows.reduce((n, row) => n + row.records.length, 0);
  return (
    <details className="development-values development-events refined-events chronological-events">
      <summary>
        <span>
          <strong id="events-heading">Events in interval</strong>
          <small>
            {timeLabel(interval.from)}–{timeLabel(interval.to)}
          </small>
        </span>
        <span className="development-values__action">
          {rows.length} events{" "}
          {rows.length !== recordCount ? (
            <small>({recordCount} records)</small>
          ) : null}
          <i aria-hidden="true">›</i>
        </span>
      </summary>
      {rows.length ? (
        <ol
          tabIndex={0}
          aria-label={`${rows.length} interval events, ${recordCount} complete source records`}
        >
          {rows.map((row) => {
            const ability = row.identity && row.abilitySlot ? resolveAbilityAsset(assets, row.identity.championId, row.abilitySlot) : undefined;
            const object = row.objectAssetKey ? resolveEventAsset(assets, row.objectAssetKey) : undefined;
            const subject = row.abilitySlot && ability ? `${row.abilitySlot} · ${ability.name}` : row.subject;
            return (
            <li
              key={row.id}
              data-event-type={row.type}
              data-source-count={row.records.length}
              data-actor-relation={row.actorRelation}
              data-object-relation={row.objectRelation}
            >
              <time
                className="development-number"
                title={`Exact recorded timestamp: ${timeLabel(row.timestampMs, true)}`}
              >
                {timeLabel(row.timestampMs, (secondCounts.get(Math.floor(row.timestampMs / 1000)) ?? 0) > 1)}
              </time>
              <span className="development-event-visuals">
                {row.identity ? (
                  <EventChampion person={row.identity} assets={assets} />
                ) : null}
                <EventSymbol row={row} assets={assets} />
                {ability ? <GameAssetIcon asset={ability} fallback={row.abilitySlot!} className="development-event-icon" /> : null}
                {object && !row.itemId ? <GameAssetIcon asset={object} fallback="?" className="development-event-icon" /> : null}
                {row.victims.length ? (
                  <span className="refined-event-victims">
                    {row.victims.map((person, index) => (
                      <EventChampion
                        key={`${person.participantId}:${index}`}
                        person={person}
                        assets={assets}
                        killed
                      />
                    ))}
                  </span>
                ) : null}
                {row.itemId ? (
                  <AssetTooltip
                    asset={row.item}
                    kind="item"
                    patch={assets?.assetVersion}
                    fallback="?"
                    iconClass="development-event-icon"
                  />
                ) : null}
              </span>
              <span className="refined-event-copy">
                <strong>{row.identityText}</strong>
                {row.actorRelation !== "unknown" ? <span className="event-relation" data-relation={row.actorRelation}>{row.actorRelation === "ally" ? "Ally" : "Enemy"}</span> : null}
                <span className="refined-event-action">
                  {[row.action, subject].filter(Boolean).join(" ")}
                  {row.type === "ITEM_DESTROYED" ? " from inventory" : ""}
                </span>
                {row.victims.length > 1 ? (
                  <small className="refined-event-note">
                    {row.victims
                      .map((p) => participantName(p, assets))
                      .join(", ")}
                  </small>
                ) : null}
                {row.note ? (
                  <small className="refined-event-note">{row.note}</small>
                ) : null}
                {row.type === "CHAMPION_KILL" && !row.assists.length ? (
                  <small className="refined-event-note">
                    {!row.records[0].assistersObserved ? "Assists not recorded." :
                      row.records[0].assisterParticipantIds.length ? "Assist participants unavailable." : "No assists recorded."}
                  </small>
                ) : null}
                {row.assists.length ? (
                  <small className="development-event-assists">
                    <span>Assists:</span>
                    {row.assists.map((person, index) => (
                      <span key={person.participantId}>
                        <EventChampion person={person} assets={assets} />
                        <span>
                          {participantName(person, assets)}
                          {index < row.assists.length - 1 ? "," : ""}
                        </span>
                      </span>
                    ))}
                  </small>
                ) : null}
                <details className="refined-event-record">
                  <summary>
                    {row.records.length > 1
                      ? `${row.records.length} source records`
                      : "Record details"}
                  </summary>
                  <p>Exact recorded time: {timeLabel(row.timestampMs, true)}</p>
                  {row.records.length > 1 ? (
                    <p>Ward placement and its matching inventory update.</p>
                  ) : null}
                  <pre>
                    {JSON.stringify(
                      row.records.length === 1 ? row.records[0] : row.records,
                      null,
                      2,
                    )}
                  </pre>
                  {row.relatedKillRecords.length ? (
                    <>
                      <p>
                        Victims matched from this champion’s preceding{" "}
                        {row.relatedKillRecords.length} kill records. The final
                        kill and multikill marker share the exact timestamp.
                        These kills also appear separately in the chronology.
                      </p>
                      <pre>
                        {JSON.stringify(row.relatedKillRecords, null, 2)}
                      </pre>
                    </>
                  ) : null}
                </details>
              </span>
            </li>
          );
          })}
        </ol>
      ) : (
        <p>No recorded events fall inside these exact endpoints.</p>
      )}
    </details>
  );
}
