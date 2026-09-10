import Link from "next/link";
import type { ReactNode } from "react";
import { PRODUCT_NAME } from "../lib/product";
import { DevelopmentFooter } from "./development-footer";

export function EntryShell({ children, active, compact = false }: {
  children: ReactNode; active?: "home" | "search"; compact?: boolean;
}) {
  return <main className="development-app entry-app">
    <header className="development-nav">
      <Link className="development-brand" href="/">{PRODUCT_NAME}</Link>
      <nav aria-label="Product navigation">
        <Link href="/" aria-current={active === "home" ? "page" : undefined}>Home</Link>
        <Link href="/search" aria-current={active === "search" ? "page" : undefined}>Player search</Link>
      </nav>
      <span className="development-region">NA1 — Solo/Duo</span>
    </header>
    <div className={`development-page entry-page${compact ? " entry-page--compact" : ""}`}>{children}</div>
    <DevelopmentFooter />
  </main>;
}
