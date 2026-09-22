import { render } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { BeforeSend } from "@vercel/analytics/react";
import { SiteAnalytics } from "./site-analytics";

const navigation = vi.hoisted(() => ({ pathname: "/search" }));
vi.mock("next/navigation", () => ({ usePathname: () => navigation.pathname }));

beforeEach(() => {
  navigation.pathname = "/search";
  delete window.va;
  delete window.vaq;
});

it("keeps encoded player names out of SDK route metadata across navigation", () => {
  navigation.pathname = "/summoners/kr/Private%20Name-KR1";
  const { rerender } = render(<SiteAnalytics enabled />);
  expect(window.vaq?.filter(([name]) => name === "pageview").at(-1)?.[1]).toEqual({
    route: "/summoners/[region]/[riotId]", path: navigation.pathname,
  });
  navigation.pathname = "/summoners/kr/Another%20Name-KR1";
  rerender(<SiteAnalytics enabled />);
  expect(window.vaq?.filter(([name]) => name === "pageview")).toHaveLength(2);
  expect(window.vaq?.filter(([name]) => name === "pageview").at(-1)?.[1]).toEqual({
    route: "/summoners/[region]/[riotId]", path: navigation.pathname,
  });
});

afterEach(() => {
  document.head.querySelectorAll('script[data-sdkn^="@vercel/analytics"]').forEach(script => script.remove());
  delete window.va;
  delete window.vaq;
  delete window.vam;
});

it("does not load analytics when collection is disabled", () => {
  render(<SiteAnalytics enabled={false} />);
  expect(document.head.querySelector('script[data-sdkn^="@vercel/analytics"]')).toBeNull();
  expect(window.va).toBeUndefined();
});

it("wires the SDK to redact page identifiers and query strings before collection", () => {
  render(<SiteAnalytics enabled />);
  expect(document.head.querySelector('script[data-sdkn^="@vercel/analytics"]')).not.toBeNull();
  const beforeSend = window.vaq?.find(([name]) => name === "beforeSend")?.[1] as BeforeSend;
  expect(beforeSend).toBeTypeOf("function");
  const cases = [
    ["/summoners/kr/Private%20Name-KR1?runId=private-run#details", "/summoners/[region]/[riotId]"],
    ["/matches/KR_123456/development?historyRunId=private-run&focus=6", "/matches/[matchId]/development"],
    ["/matches/NA1_123456?focus=6", "/matches/[matchId]"],
    ["/search?runId=private-run", "/search"],
    ["/?utm_source=portfolio", "/"],
    ["/privacy", "/privacy"],
  ];
  for (const [path, expected] of cases) {
    const event = { type: "pageview" as const, url: `https://lolmatchanalysis.app${path}` };
    expect(beforeSend(event)).toEqual({ type: "pageview", url: `https://lolmatchanalysis.app${expected}` });
    expect(event.url).toBe(`https://lolmatchanalysis.app${path}`);
  }
  expect(beforeSend({ type: "pageview", url: "https://lolmatchanalysis.app/unknown/private-name" })).toBeNull();
  expect(beforeSend({ type: "pageview", url: "not-a-url" })).toBeNull();
});
