import type { ReactNode } from "react";
import { publicContactEmail } from "../lib/prototype-config";
import { EntryShell } from "./entry-shell";

export function PolicyPage({ title, children }: { title: string; children: ReactNode }) {
  return <EntryShell>
    <article className="policy-page">
      <h1>{title}</h1>
      <p className="policy-date">Updated September 12, 2026</p>
      {children}
      <h2>Contact</h2>
      <PolicyContact />
    </article>
  </EntryShell>;
}

function PolicyContact() {
  const email = publicContactEmail();
  return <p>{email
    ? <>Questions or data requests: <a href={`mailto:${email}`}>{email}</a>.</>
    : "A public contact address has not been configured in this local build."}</p>;
}
