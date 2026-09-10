import type { Metadata } from "next";
import { PolicyPage } from "../../components/policy-page";
import { PRODUCT_NAME } from "../../lib/product";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: `Privacy Policy — ${PRODUCT_NAME}` };

export default function PrivacyPage() {
  return <PolicyPage title="Privacy Policy">
    <p>{PRODUCT_NAME} is a pre-release League of Legends match-analysis prototype operated by Liam Kinnally for testing and Riot review. This policy describes the current prototype.</p>

    <h2>Information the app uses</h2>
    <p>When you search for a player, the app sends the game name and tag line you enter to its server and queries Riot’s APIs. It retrieves account identifiers, in-game names, recent match details and timelines, and current queue-specific ranks. Match records include information about all ten participants.</p>
    <p>The server stores Riot account identifiers, observed names, match records, captured API responses and lookup status in PostgreSQL. Public pages show selected match and player information. They do not expose PUUIDs, raw provider responses or API credentials. Anyone with a match or search URL may be able to view the displayed results.</p>

    <h2>Why this information is used</h2>
    <p>Data is used to find recent matches, display final results and recorded changes over time, show current ranks, reuse previously retrieved games, and diagnose failures. Current ranks are not historical ranks or MMR.</p>

    <h2>Hosting and external requests</h2>
    <p>The hosted prototype uses Vercel for its frontend and Railway for its backend and database. These providers process network requests and may keep operational logs, including IP addresses, request URLs and technical metadata. The app also loads League images from Riot’s Data Dragon and other game-asset CDNs; those requests disclose your IP address to the asset host.</p>
    <p>See the privacy information from <a href="https://vercel.com/legal/privacy-policy">Vercel</a>, <a href="https://railway.com/legal/privacy">Railway</a> and <a href="https://www.riotgames.com/en/privacy-notice">Riot Games</a>.</p>

    <h2>Cookies, accounts and advertising</h2>
    <p>The app has no user accounts, advertising or payments, and does not intentionally set tracking cookies. This prototype does not sell player data. Hosting and security infrastructure may process technical information needed to deliver and protect the site.</p>

    <h2>Retention and requests</h2>
    <p>Stored matches and retrieval records remain until manually removed or the prototype database is reset. There is currently no automatic deletion schedule. Short-lived search and rank caches do not delete the underlying stored match records. Database backups may retain earlier copies.</p>
    <p>You can contact the operator below about information displayed by this prototype or request correction or removal from its stored data. This app cannot change Riot’s source records. Please include only the Riot ID or match URL needed to identify the record, never your password or API key.</p>

    <h2>Changes</h2>
    <p>The prototype and this policy may change as development continues. The date above identifies this version.</p>
  </PolicyPage>;
}
