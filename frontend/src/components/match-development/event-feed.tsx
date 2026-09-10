import { useMemo } from "react";
import { GameAssetIcon } from "../game-asset-icon";
import { AssetTooltip } from "../asset-tooltip";
import { eventDisplayRows } from "../../lib/development/events";
import { timeLabel } from "../../lib/development/chart-model";
import { participantName } from "../../lib/development/results";
import type {
  DevelopmentInterval,
  MatchDevelopment,
  MatchDevelopmentParticipant,
} from "../../lib/development/types";
import type { GameAssetCatalog } from "../../lib/game-assets/types";

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
function EventSymbol({
  type,
  assets,
  version,
}: {
  type: string;
  assets: GameAssetCatalog | null;
  version: string;
}) {
  const file = (
    {
      CHAMPION_KILL: "scoreboard-sword-icon.svg",
      CHAMPION_SPECIAL_KILL: "scoreboard-sword-icon.svg",
      ITEM_PURCHASED: "scoreboard-coins-icon.svg",
      ITEM_SOLD: "scoreboard-coins-icon.svg",
      WARD_PLACED: "scoreboard-stat-switcher-eye.svg",
    } as Record<string, string>
  )[type];
  const patch = version.split(".").slice(0, 2).join(".");
  const imageUrl =
    type === "WARD_KILL"
      ? assets?.items["3364"]?.imageUrl
      : file && /^\d+\.\d+$/.test(patch)
        ? `https://raw.communitydragon.org/${patch}/plugins/rcp-fe-lol-postgame/global/default/${file}`
        : null;
  if (imageUrl)
    return (
      <span
        data-event-symbol={type}
        className="refined-event-symbol"
        aria-hidden="true"
      >
        <GameAssetIcon
          asset={{ name: type, imageUrl }}
          fallback={type.includes("KILL") ? "×" : "+"}
          className="refined-event-symbol-image"
        />
      </span>
    );
  if (type === "ITEM_DESTROYED")
    return (
      <svg
        className="refined-event-symbol"
        viewBox="0 0 20 20"
        fill="none"
        aria-hidden="true"
      >
        <path d="M4 10h12" stroke="currentColor" strokeWidth="1.5" />
      </svg>
    );
  if (type === "ITEM_UNDO")
    return (
      <svg
        className="refined-event-symbol"
        viewBox="0 0 20 20"
        fill="none"
        aria-hidden="true"
      >
        <path
          d="m7 3-4 4 4 4M3 7h8a5 5 0 0 1 0 10H8"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinejoin="round"
        />
      </svg>
    );
  return null;
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
    () => eventDisplayRows(data.events, data.roster, assets),
    [data.events, data.roster, assets],
  );
  const rows = allRows.filter(
    (e) => e.timestampMs >= interval.from && e.timestampMs <= interval.to,
  );
  const recordCount = rows.reduce((n, row) => n + row.records.length, 0);
  return (
    <details className="development-values development-events refined-events">
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
          {rows.map((row) => (
            <li
              key={row.id}
              data-event-type={row.type}
              data-source-count={row.records.length}
            >
              <time
                className="development-number"
                title={`Exact recorded timestamp: ${timeLabel(row.timestampMs, true)}`}
              >
                {timeLabel(row.timestampMs)}
              </time>
              <span className="development-event-visuals">
                {row.identity ? (
                  <EventChampion person={row.identity} assets={assets} />
                ) : null}
                <EventSymbol
                  type={row.type}
                  assets={assets}
                  version={data.summary.gameVersion}
                />
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
                <span className="refined-event-action">
                  {[row.action, row.subject].filter(Boolean).join(" ")}
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
          ))}
        </ol>
      ) : (
        <p>No recorded events fall inside these exact endpoints.</p>
      )}
    </details>
  );
}
