"use client";

import { Analytics, type BeforeSendEvent } from "@vercel/analytics/react";
import { usePathname } from "next/navigation";

function analyticsRoute(pathname: string): string | null {
  if (/^\/summoners\/[^/]+\/[^/]+\/?$/.test(pathname)) {
    return "/summoners/[region]/[riotId]";
  }
  if (/^\/matches\/[^/]+\/development\/?$/.test(pathname)) {
    return "/matches/[matchId]/development";
  }
  if (/^\/matches\/[^/]+\/?$/.test(pathname)) {
    return "/matches/[matchId]";
  }
  return ["/", "/search", "/privacy", "/terms"].includes(pathname) ? pathname : null;
}

function beforeSend(event: BeforeSendEvent): BeforeSendEvent | null {
  try {
    const url = new URL(event.url);
    const route = analyticsRoute(url.pathname);
    if (!route) return null;
    url.pathname = route;
    url.search = "";
    url.hash = "";
    return { ...event, url: url.toString() };
  } catch {
    return null;
  }
}

export function SiteAnalytics({ enabled }: { enabled: boolean }) {
  const pathname = usePathname();
  const route = pathname ? analyticsRoute(pathname) : null;

  // Set route metadata explicitly so encoded Riot names cannot become route labels.
  return enabled && route ? (
    <Analytics
      framework="next"
      route={route}
      path={pathname}
      beforeSend={beforeSend}
      basePath={process.env.NEXT_PUBLIC_VERCEL_OBSERVABILITY_BASEPATH}
      configString={process.env.NEXT_PUBLIC_VERCEL_OBSERVABILITY_CLIENT_CONFIG}
    />
  ) : null;
}
