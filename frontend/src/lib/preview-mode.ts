import "server-only";

export function isSamplePreview(environment: NodeJS.ProcessEnv = process.env): boolean {
  const eligible = environment.VERCEL === "1"
    ? environment.VERCEL_ENV === "preview"
    : !environment.VERCEL_ENV && environment.LEAGUE_ANALYSIS_RUNTIME === "verification";
  return eligible && environment.PREVIEW_DATA_SOURCE === "sample";
}
