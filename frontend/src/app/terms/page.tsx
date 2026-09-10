import type { Metadata } from "next";
import { PolicyPage } from "../../components/policy-page";
import { PRODUCT_NAME } from "../../lib/product";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: `Terms of Service — ${PRODUCT_NAME}` };

export default function TermsPage() {
  return <PolicyPage title="Terms of Service">
    <p>{PRODUCT_NAME} is a free, pre-release prototype operated by Liam Kinnally for testing and Riot review. It is not an official Riot Games service, and production approval has not been claimed.</p>

    <h2>Using the prototype</h2>
    <p>You may use the site to inspect the included sample and supported League of Legends match records. Live lookup currently supports NA1 players, ranked Solo/Duo and the latest five matches. No Riot password or account sign-in is required.</p>
    <p>Do not bypass request limits, attempt unauthorized access, disrupt the service, harvest data in bulk, or use displayed player information to harass others. Access may be limited to protect the prototype and its API allowance.</p>

    <h2>Availability and accuracy</h2>
    <p>The prototype may be changed, interrupted or withdrawn. Live lookup depends on Riot API availability, key validity and rate limits. It may be temporarily unavailable during testing or deployment.</p>
    <p>Statistics reflect recorded API data and may be missing, delayed or inconsistent. Chart samples do not establish exact state between timestamps or explain what caused a result. Ranks are current queue-specific observations, not rank at the time of the match or MMR. The sample match is clearly labeled as invented data.</p>

    <h2>Riot Games and other materials</h2>
    <p>League of Legends, Riot Games and associated game assets belong to their respective owners. This prototype does not grant rights to those materials. Riot’s own terms apply to its products and services.</p>

    <h2>Privacy and changes</h2>
    <p>The <a href="/privacy">Privacy Policy</a> explains what this prototype retrieves and stores. These terms may be updated as the prototype changes; the date above identifies this version. Questions can be sent to the contact below.</p>
  </PolicyPage>;
}
