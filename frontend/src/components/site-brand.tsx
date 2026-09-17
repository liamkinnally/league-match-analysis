import Image from "next/image";
import Link from "next/link";
import { PRODUCT_NAME } from "../lib/product";

export function SiteBrand() {
  return <Link className="development-brand" href="/" translate="no">
    <Image className="development-brand__mark" src="/brand/logo-mark.svg" alt="" width={28} height={28} />
    <span className="development-brand__name">{PRODUCT_NAME}</span>
  </Link>;
}
