import { PRODUCT_NAME } from "../lib/product";

export function SiteBanner() {
  return <aside className="site-banner" aria-label="About this site">
    <p><strong>{PRODUCT_NAME}</strong> — Explore match history, timelines, and player comparisons.</p>
  </aside>;
}
