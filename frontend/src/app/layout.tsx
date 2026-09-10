import type { Metadata } from "next";
import type { ReactNode } from "react";
import "@fontsource-variable/instrument-sans";
import "./globals.css";
import "./reviewed-match.css";
import "./entry-pages.css";
import "./prototype.css";
import { PRODUCT_NAME } from "../lib/product";
import { PrototypeNotice } from "../components/prototype-notice";

export const metadata: Metadata = {
  title: PRODUCT_NAME,
  description: "Pre-release League of Legends match-analysis prototype for testing and Riot review.",
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return <html lang="en"><body className="prototype-shell"><PrototypeNotice />{children}</body></html>;
}
