"use client";

import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import type { GameAsset } from "../lib/game-assets/types";
import { GameAssetIcon } from "./game-asset-icon";
import { numberText } from "../lib/development/results";

export function CompactTooltip({
  label,
  children,
  content,
  className = "",
}: {
  label: string;
  children: ReactNode;
  content: ReactNode;
  className?: string;
}) {
  const id = useId();
  const [position, setPosition] = useState<{
    left: number;
    top: number;
    above: boolean;
  } | null>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const popup = useRef<HTMLDivElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const clear = useCallback(() => {
    if (timer.current) clearTimeout(timer.current);
  }, []);
  const show = useCallback(() => {
    clear();
    const box = trigger.current?.getBoundingClientRect();
    if (!box) return;
    if (box.bottom < 0 || box.top > window.innerHeight) {
      setPosition(null);
      return;
    }
    const above = box.bottom + 210 > window.innerHeight && box.top > 210;
    setPosition({
      left: Math.max(
        8,
        Math.min(box.left + box.width / 2 - 120, window.innerWidth - 248),
      ),
      top: above ? box.top - 8 : box.bottom + 8,
      above,
    });
  }, [clear]);
  const leave = () => {
    clear();
    timer.current = setTimeout(() => setPosition(null), 120);
  };
  useEffect(() => {
    if (!position) return;
    const dismiss = (event: KeyboardEvent) => {
      if (event.key === "Escape") setPosition(null);
    };
    const outside = (event: PointerEvent) => {
      if (
        !trigger.current?.contains(event.target as Node) &&
        !popup.current?.contains(event.target as Node)
      )
        setPosition(null);
    };
    const scroll = (event: Event) => {
      if (event.target instanceof Node && popup.current?.contains(event.target)) return;
      if (document.activeElement === trigger.current) show();
      else setPosition(null);
    };
    document.addEventListener("keydown", dismiss);
    document.addEventListener("pointerdown", outside);
    window.addEventListener("scroll", scroll, true);
    window.addEventListener("resize", scroll);
    return () => {
      document.removeEventListener("keydown", dismiss);
      document.removeEventListener("pointerdown", outside);
      window.removeEventListener("scroll", scroll, true);
      window.removeEventListener("resize", scroll);
    };
  }, [position, show]);
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );
  return (
    <>
      <button
        ref={trigger}
        className={`asset-tooltip-trigger ${className}`.trim()}
        type="button"
        aria-label={label}
        aria-describedby={position ? id : undefined}
        onMouseEnter={show}
        onMouseLeave={leave}
        onFocus={show}
        onBlur={leave}
        onClick={(event) => {
          event.stopPropagation();
          show();
        }}
      >
        {children}
      </button>
      {position
        ? createPortal(
            <div
              ref={popup}
              id={id}
              role="tooltip"
              onClick={(event) => event.stopPropagation()}
              className="refined-item-tooltip"
              onMouseEnter={clear}
              onMouseLeave={leave}
              style={{
                left: position.left,
                top: position.top,
                transform: position.above ? "translateY(-100%)" : undefined,
              }}
            >
              {content}
            </div>,
            document.body,
          )
        : null}
    </>
  );
}

export function AssetTooltip({
  asset,
  kind,
  patch,
  fallback,
  iconClass,
}: {
  asset?: GameAsset;
  kind: "item" | "spell";
  patch?: string;
  fallback: string;
  iconClass: string;
}) {
  const name =
    asset?.name ??
    (kind === "spell" ? "Summoner spell unavailable" : "Item unavailable");
  const price = (value: number | null | undefined) =>
    Number.isFinite(value) ? `${numberText(value)} gold` : "Unavailable";
  return (
    <CompactTooltip
      label={name}
      content={
        <>
          <div className="refined-item-tooltip__name">
            <GameAssetIcon
              asset={asset}
              fallback="?"
              className="tooltip-asset"
            />
            <strong>{name}</strong>
          </div>
          {kind === "spell" && asset?.description ? (
            <p className="refined-item-tooltip__description">
              {asset.description}
            </p>
          ) : null}
          <dl>
            {kind === "item" ? (
              <>
                <div>
                  <dt>Total cost</dt>
                  <dd>{price(asset?.gold?.total)}</dd>
                </div>
                <div>
                  <dt>Combine cost</dt>
                  <dd>{price(asset?.gold?.base)}</dd>
                </div>
                <div>
                  <dt>Sell value</dt>
                  <dd>{price(asset?.gold?.sell)}</dd>
                </div>
              </>
            ) : (
              <div>
                <dt>Base cooldown</dt>
                <dd>
                  {asset?.cooldown?.length
                    ? `${asset.cooldown.join(" / ")}s`
                    : "Unavailable"}
                </dd>
              </div>
            )}
          </dl>
          <small>
            {patch ? `Catalog patch ${patch}` : "Catalog unavailable"}
            {kind === "item"
              ? " — catalog prices; transaction cost may differ."
              : " — cooldown before in-game modifiers."}
          </small>
        </>
      }
    >
      <GameAssetIcon asset={asset} fallback={fallback} className={iconClass} />
    </CompactTooltip>
  );
}
