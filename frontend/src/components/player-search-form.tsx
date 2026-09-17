"use client";
import { useEffect, useId, useRef, useState } from "react";
import { GameAssetIcon } from "./game-asset-icon";
import { useCurrentProfileAssets } from "../lib/game-assets/use-game-assets";
import { isPlatform, parseRiotId, regions, regionFor, type PlayerIdentity, type Platform } from "../lib/player-lookup/regions";
import { parseSuggestions, type PlayerSuggestion } from "../lib/player-lookup/suggestions";

type Props = { identity?: PlayerIdentity; submitting: boolean; profile?: boolean; onSubmit: (input: PlayerIdentity & { queueId: number }) => unknown };
export function PlayerSearchForm({ identity, submitting, profile = false, onSubmit }: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [editedValue, setEditedValue] = useState<string | null>(null);
  const [editedPlatform, setEditedPlatform] = useState<Platform | null>(null);
  const value = editedValue ?? (identity?.gameName ? `${identity.gameName}#${identity.tagLine}` : "");
  const platform = editedPlatform ?? identity?.platform ?? "NA1";
  const [focused, setFocused] = useState(false);
  const [dismissed, setDismissed] = useState(false);
  const [active, setActive] = useState(-1);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<{ key: string; suggestions: PlayerSuggestion[]; unavailable: boolean } | null>(null);
  const id = useId(), listId = `${id}-suggestions`;
  const assets = useCurrentProfileAssets();
  const query = value.trim(), key = `${platform}:${query}`;
  const open = focused && !dismissed && query.length > 0;
  const suggestions = result?.key === key ? result.suggestions : [];
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const response = await fetch(`/api/player-suggestions?${new URLSearchParams({ platform, q: query })}`, { cache: "no-store", signal: controller.signal });
        if (!response.ok) throw new Error("SUGGESTIONS_UNAVAILABLE");
        const suggestions = parseSuggestions(await response.json());
        if (suggestions.some(suggestion => suggestion.platform !== platform)) throw new Error("REGION_MISMATCH");
        if (!controller.signal.aborted) setResult({ key, suggestions, unavailable: false });
      } catch {
        if (!controller.signal.aborted) setResult({ key, suggestions: [], unavailable: true });
      }
    }, 150);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [key, platform, query, open]);
  const submit = (identity: PlayerIdentity) => {
    setError(null); setDismissed(true); setActive(-1);
    setEditedValue(`${identity.gameName}#${identity.tagLine}`);
    void onSubmit({ gameName: identity.gameName, tagLine: identity.tagLine, platform: identity.platform, queueId: 0 });
  };
  return <form className={`player-search player-search--combined${profile ? " player-search--profile" : ""}`} onSubmit={event => {
    event.preventDefault();
    const parsed = parseRiotId(value);
    if (!parsed) { setError("Enter the full Riot ID, including #tag, or select a cached profile."); inputRef.current?.focus(); return; }
    submit({ ...parsed, platform });
  }}>
    <div className="player-search__field" onBlur={event => {
      if (!event.currentTarget.contains(event.relatedTarget)) { setFocused(false); setActive(-1); }
    }}>
      <label className="player-search__riot-label" htmlFor={`${id}-riot`}>Riot ID</label>
      <div className="player-search__input-group">
        <label className="player-search__region"><span className="profile-sr-only">Region</span>
          <select name="platform" title={regionFor(platform).name} value={platform} onChange={event => {
            if (isPlatform(event.target.value)) setEditedPlatform(event.target.value);
            setActive(-1); setDismissed(false); setError(null);
          }}>{regions.map(region => <option value={region.platform} key={region.platform}>{region.label}</option>)}</select>
        </label>
        <div className="player-search__identity-input">
        <input ref={inputRef} id={`${id}-riot`} name="riotId" role="combobox" aria-autocomplete="list" aria-expanded={open}
          aria-controls={open ? listId : undefined} aria-activedescendant={open && active >= 0 && suggestions[active] ? `${id}-option-${active}` : undefined}
          aria-describedby={error ? `${id}-error` : undefined} aria-invalid={error ? true : undefined}
          placeholder={`Game name + #${regionFor(platform).exampleTag}`} maxLength={81} required autoComplete="off" autoCapitalize="none" spellCheck={false} value={value}
          onFocus={() => { setFocused(true); setDismissed(false); }}
          onChange={event => { setEditedValue(event.target.value); setDismissed(false); setActive(-1); setError(null); }}
          onKeyDown={event => {
            if (event.key === "Escape") { event.preventDefault(); setDismissed(true); setActive(-1); }
            if ((event.key === "ArrowDown" || event.key === "ArrowUp") && suggestions.length > 0) {
              event.preventDefault(); setDismissed(false);
              setActive(previous => event.key === "ArrowDown" ? (previous + 1) % suggestions.length : (previous <= 0 ? suggestions.length - 1 : previous - 1));
            }
            if (event.key === "Enter" && open && active >= 0 && suggestions[active]) { event.preventDefault(); submit(suggestions[active]); }
          }} />
        {!value && <span className="player-search__placeholder" aria-hidden="true">Game name + <span>#{regionFor(platform).exampleTag}</span></span>}
        </div>
      </div>
      {open && <div className="player-search__suggestions">
        <p className="player-search__suggestions-label">Cached profiles · {regionFor(platform).label}</p>
        <span className="profile-sr-only" aria-live="polite">{result?.key === key && suggestions.length > 0 ? `${suggestions.length} cached profiles available. Use the arrow keys to select.` : ""}</span>
        <ul id={listId} role="listbox" aria-label="Cached player profiles">
          {suggestions.map((suggestion, index) => <li id={`${id}-option-${index}`} role="option" aria-selected={active === index}
            key={`${suggestion.platform}:${suggestion.gameName}#${suggestion.tagLine}`} onMouseDown={event => event.preventDefault()}
            onMouseMove={() => setActive(index)} onClick={() => submit(suggestion)} title={`${suggestion.gameName}#${suggestion.tagLine}`}>
            <GameAssetIcon asset={suggestion.profileIconId === null ? undefined : assets?.profileIcons?.[String(suggestion.profileIconId)]}
              fallback={suggestion.gameName.slice(0, 2)} className="player-search__suggestion-icon" />
            <span className="player-search__suggestion-identity"><strong>{suggestion.gameName}<span>#{suggestion.tagLine}</span></strong>
              <small>{suggestion.summonerLevel === null ? regionFor(suggestion.platform).name : `Level ${suggestion.summonerLevel.toLocaleString("en-US")}`}</small></span>
          </li>)}
        </ul>
        {!suggestions.length && <p className="player-search__suggestions-empty" role="status">{result?.key !== key ? "Searching cached profiles…" : result.unavailable ? "Suggestions unavailable. Enter a full Riot ID to search." : "No cached profiles. Enter a full Riot ID to search."}</p>}
      </div>}
    </div>
    <button type="submit" disabled={submitting}>{submitting ? "Finding matches…" : "Find matches"}<span aria-hidden="true">→</span></button>
    {error && <p id={`${id}-error`} role="alert" className="player-search__error">{error}</p>}
  </form>;
}
