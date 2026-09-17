import type { Metadata } from "next";
import type { ReactNode } from "react";
import "@fontsource-variable/instrument-sans";
import "./globals.css";
import "./reviewed-match.css";
import "../components/match-development/event-feed.css";
import "./entry-pages.css";
import "./prototype.css";
import "../components/match-development/rune-view.css";
import "../components/player-profile.css";
import { PRODUCT_NAME } from "../lib/product";
import { SiteBanner } from "../components/site-banner";

const description = "Explore League of Legends match history, timelines, and player comparisons.";
const socialCard = {
  url: "https://lolmatchanalysis.app/social-card.png",
  width: 1734,
  height: 907,
  alt: "LoL Match Analysis: match history, timelines, and player comparisons.",
  type: "image/png",
};

export const metadata: Metadata = {
  metadataBase: new URL("https://lolmatchanalysis.app"),
  title: PRODUCT_NAME,
  description,
  robots: { index: false, follow: false },
  icons: {
    icon: [
      { url: "/favicon.ico?v=bc5ac15a", sizes: "16x16 32x32 48x48", type: "image/x-icon" },
      { url: "/favicon.png?v=bc5ac15a", sizes: "256x256", type: "image/png" },
    ],
    apple: { url: "/apple-touch-icon.png?v=bc5ac15a", sizes: "180x180", type: "image/png" },
  },
  openGraph: {
    type: "website",
    title: PRODUCT_NAME,
    description,
    siteName: PRODUCT_NAME,
    images: [socialCard],
  },
  twitter: {
    card: "summary_large_image",
    title: PRODUCT_NAME,
    description,
    images: [socialCard],
  },
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return <html lang="en"><body className="site-shell"><SiteBanner />{children}</body></html>;
}
