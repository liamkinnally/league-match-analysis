import Link from "next/link";
import type { ReactNode } from "react";
import { DevelopmentFooter } from "./development-footer";
import { SiteBrand } from "./site-brand";

export function EntryShell({ children, active, compact = false }: {
  children: ReactNode; active?: "home" | "search" | "sample" | "leaderboard"; compact?: boolean;
}) {
  return <main className={`development-app entry-app${active === "search" || active === "sample" ? " profile-app" : ""}`}>
    <header className="development-nav">
      <SiteBrand />
      <nav aria-label="Product navigation">
        <Link href="/" aria-current={active === "home" ? "page" : undefined}>Home</Link>
        {active === "sample"
          ? <span aria-current="page">Sample profile</span>
          : <Link href="/search" aria-current={active === "search" ? "page" : undefined}>Match history</Link>}
        <Link href="/leaderboard" aria-current={active === "leaderboard" ? "page" : undefined}>Leaderboard</Link>
      </nav>
    </header>
    <div className={`development-page entry-page${compact ? " entry-page--compact" : ""}`}>{children}</div>
    <DevelopmentFooter />
  </main>;
}
