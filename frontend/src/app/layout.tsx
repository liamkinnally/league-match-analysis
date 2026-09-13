import type { Metadata } from "next";
import type { ReactNode } from "react";
import "@fontsource-variable/instrument-sans";
import "./globals.css";
import "./reviewed-match.css";
import "./entry-pages.css";
import "./prototype.css";
import { PRODUCT_NAME } from "../lib/product";
import { SiteBanner } from "../components/site-banner";

export const metadata: Metadata = {
  title: PRODUCT_NAME,
  description: "Explore League of Legends match history, timelines, and player comparisons.",
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return <html lang="en"><body className="site-shell"><SiteBanner />{children}</body></html>;
}
