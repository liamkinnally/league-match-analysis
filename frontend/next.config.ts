import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  agentRules: false,
  // Vercel's adapter owns server packaging; Docker needs standalone output.
  output: process.env.VERCEL === "1" ? undefined : "standalone",
};

export default nextConfig;
