import Link from "next/link";
import { PRODUCT_NAME } from "../lib/product";

export function DevelopmentFooter() {
  return (
    <footer className="development-footer">
      <nav className="prototype-links" aria-label="Policies">
        <Link href="/privacy">Privacy Policy</Link>
        <Link href="/terms">Terms of Service</Link>
      </nav>
      {PRODUCT_NAME} isn&apos;t endorsed by Riot Games and doesn&apos;t reflect the views or opinions of Riot Games or anyone officially involved in producing or managing Riot Games properties. Riot Games, and all associated properties are trademarks or registered trademarks of Riot Games, Inc.
    </footer>
  );
}
