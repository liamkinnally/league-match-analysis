import "server-only";
import { isSamplePreview } from "./preview-mode";

function serviceToken(): string | undefined {
  const token = process.env.BACKEND_SERVICE_TOKEN;
  const required = process.env.BACKEND_AUTH_REQUIRED === "true" || process.env.VERCEL === "1";
  if (!token) {
    if (required) throw new Error("BACKEND_SERVICE_TOKEN_NOT_CONFIGURED");
    return undefined;
  }
  if (token.length < 32 || !/^[\x21-\x7e]+$/.test(token)) {
    throw new Error("BACKEND_SERVICE_TOKEN_INVALID");
  }
  return token;
}

function backendUrl(path: string): URL {
  if (!path.startsWith("/") || path.startsWith("//") || path.includes("\\")
      || /[\u0000-\u001f\u007f]/.test(path) || /(^|\/)\.\.?($|[/?#])/.test(path)) {
    throw new Error("BACKEND_PATH_INVALID");
  }
  const configured = process.env.BACKEND_URL;
  if (!configured) throw new Error("BACKEND_URL_NOT_CONFIGURED");
  const origin = new URL(configured);
  if (!/^https?:$/.test(origin.protocol) || origin.username || origin.password
      || (process.env.VERCEL === "1" && origin.protocol !== "https:")) {
    throw new Error("BACKEND_URL_INVALID");
  }
  const resolved = new URL(path, origin.origin);
  if (resolved.origin !== origin.origin) throw new Error("BACKEND_PATH_INVALID");
  return resolved;
}

export async function backendFetch(path: string, init: RequestInit = {}): Promise<Response> {
  if (isSamplePreview()) throw new Error("BACKEND_DISABLED_IN_SAMPLE_PREVIEW");
  const url = backendUrl(path);
  const headers = new Headers(init.headers);
  headers.delete("Authorization");
  const token = serviceToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const request = new Request(url, { ...init, headers, redirect: "manual" });
  return fetch(request);
}
